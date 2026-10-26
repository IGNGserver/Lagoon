package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.Diagnostics
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2-HMAC-SHA256 password hashes: `pbkdf2-sha256$<iterations>$<salt>$<hash>`. */
object PasswordHash {
  private const val ITERATIONS = 210_000
  private val random = SecureRandom()

  fun hash(password: String, iterations: Int = ITERATIONS): String {
    val salt = ByteArray(16).also(random::nextBytes)
    return "pbkdf2-sha256$$iterations$${b64(salt)}$${b64(derive(password, salt, iterations))}"
  }

  fun verify(password: String, encoded: String): Boolean {
    val parts = encoded.split('$')
    if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
    val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
    val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
    val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
    return MessageDigest.isEqual(derive(password, salt, iterations), expected)
  }

  private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
      spec.clearPassword()
    }
  }

  private fun b64(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Brute-force brake for a deliberately weak-password login. Per client address: from the fifth
 * consecutive failure every further failure locks the address for twice as long as the previous one
 * (1 s … 15 min). Across all addresses: more than 30 failures within 10 minutes spaces every failed
 * attempt at least 2 s apart. A success clears the address's record.
 */
class LoginThrottle(private val clock: () -> Long = System::currentTimeMillis) {
  private class Record(var failures: Int = 0, var lockedUntil: Long = 0)

  private val records = LinkedHashMap<String, Record>()
  private val recentFailures = ArrayDeque<Long>()
  private var globalNextAllowed = 0L

  /** Milliseconds the caller must wait before trying again; 0 when an attempt is allowed now. */
  @Synchronized fun retryAfter(address: String): Long {
    val now = clock()
    val own = records[address]?.lockedUntil?.minus(now) ?: 0
    val global = globalNextAllowed - now
    return maxOf(own, global, 0)
  }

  @Synchronized fun failed(address: String) {
    val now = clock()
    val record = records.getOrPut(address) { Record() }
    record.failures++
    if (record.failures >= FREE_ATTEMPTS) {
      val exponent = (record.failures - FREE_ATTEMPTS).coerceAtMost(20)
      record.lockedUntil = now + (1_000L shl exponent).coerceAtMost(MAX_LOCK_MS)
    }
    recentFailures.addLast(now)
    while (recentFailures.isNotEmpty() && recentFailures.first() < now - GLOBAL_WINDOW_MS) recentFailures.removeFirst()
    if (recentFailures.size > GLOBAL_LIMIT) {
      globalNextAllowed = now + GLOBAL_SPACING_MS
      if (recentFailures.size == GLOBAL_LIMIT + 1) Diagnostics.warn("Auth", "10 分钟内登录失败超过 $GLOBAL_LIMIT 次，已对所有来源限速")
    }
    // Bound memory under a distributed attack; the oldest addresses are forgotten first.
    while (records.size > MAX_RECORDS) records.remove(records.keys.first())
  }

  @Synchronized fun succeeded(address: String) { records.remove(address) }

  companion object {
    const val FREE_ATTEMPTS = 5
    const val MAX_LOCK_MS = 15 * 60_000L
    const val GLOBAL_WINDOW_MS = 10 * 60_000L
    const val GLOBAL_LIMIT = 30
    const val GLOBAL_SPACING_MS = 2_000L
    const val MAX_RECORDS = 10_000
  }
}

/**
 * The hub's single access password and the browser sessions it grants.
 *
 * - `LAGOON_PASSWORD` set: it is the password. When it changes between starts every browser is
 *   signed out. It cannot be changed from the web page.
 * - Not set: until a password exists, the web page asks for it together with a one-time setup code
 *   printed in the container log, so whoever reaches the page first cannot claim the hub.
 */
class Authenticator(private val store: HubStore, private val envPassword: String?, private val clock: () -> Long = System::currentTimeMillis) {
  val throttle = LoginThrottle(clock)
  private val random = SecureRandom()

  /** The one-time code that authorizes setting the first password; null once a password exists. */
  @Volatile var setupCode: String? = null
    private set

  init {
    val stored = store.passwordHash()
    if (!envPassword.isNullOrEmpty()) {
      if (stored == null || !PasswordHash.verify(envPassword, stored)) {
        store.setPasswordHash(PasswordHash.hash(envPassword))
        if (stored != null) Diagnostics.info("Auth", "LAGOON_PASSWORD 已变化，所有设备需要重新登录")
      }
    } else if (stored == null) {
      setupCode = newSetupCode()
      Diagnostics.info("Auth", "尚未设置访问密码。打开网页时输入初始化码：$setupCode")
    }
    store.pruneLogins(clock() - SESSION_TTL_MS)
  }

  val passwordFromEnv: Boolean get() = !envPassword.isNullOrEmpty()
  val setupRequired: Boolean get() = store.passwordHash() == null

  /** The session behind a cookie token, sliding its expiry; null when absent or expired. */
  fun session(token: String?): LoginSession? {
    if (token.isNullOrBlank() || token.length > 128) return null
    val hash = tokenHash(token)
    val session = store.login(hash) ?: return null
    val now = clock()
    if (now - session.lastSeen > SESSION_TTL_MS) { store.removeLogin(hash); return null }
    store.touchLogin(hash, now)
    return session
  }

  fun verify(password: String): Boolean = store.passwordHash()?.let { PasswordHash.verify(password, it) } == true

  /** Starts a browser session and returns its raw token for the cookie. */
  fun issue(label: String): String {
    val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    val now = clock()
    store.addLogin(LoginSession(tokenHash(token), now, now, label.take(120)))
    return token
  }

  fun setup(code: String, password: String): Boolean {
    val expected = setupCode ?: return false
    if (!MessageDigest.isEqual(normalizeCode(code).toByteArray(), normalizeCode(expected).toByteArray())) return false
    store.setPasswordHash(PasswordHash.hash(password))
    setupCode = null
    return true
  }

  fun changePassword(password: String) = store.setPasswordHash(PasswordHash.hash(password))

  fun revoke(token: String?) { if (!token.isNullOrBlank()) store.removeLogin(tokenHash(token)) }
  fun revokeAll() = store.clearLogins()

  private fun newSetupCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val chars = (1..8).map { alphabet[random.nextInt(alphabet.length)] }
    return chars.take(4).joinToString("") + "-" + chars.drop(4).joinToString("")
  }

  private fun normalizeCode(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

  companion object {
    const val COOKIE = "lagoon_session"
    const val SESSION_TTL_MS = 90L * 24 * 60 * 60 * 1000

    fun tokenHash(token: String): String =
      Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
  }
}
