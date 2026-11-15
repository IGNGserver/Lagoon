package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.RemoteExecResult
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshSecrets
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SshTransportPoolConcurrencyTest {
  private class Transport : SshTransport {
    val closed = AtomicBoolean(false)
    override val isClosed get() = closed.get()
    override fun close() { closed.set(true) }
    override fun exec(script: String, timeoutMillis: Long) = RemoteExecResult(0, "", "")
    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection = error("unused")
    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long) = !isClosed
    override fun ping(timeoutMillis: Long) = !isClosed
  }

  private val config = SshHostConfig("one", "host", "one.example", user = "user", hostKeyFingerprint = "pin")
  private val verify = SshHostKeyVerifier { true }

  @Test fun slowHandshakeDoesNotBlockAnotherDeviceOrInvalidation() {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pool = SshTransportPool(object : SshTransportFactory {
      override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
        if (config.id == "one") {
          started.countDown()
          check(release.await(5, TimeUnit.SECONDS))
        }
        return Transport()
      }
      override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey = error("unused")
    })
    val workers = Executors.newFixedThreadPool(3)
    try {
      val slow = workers.submit<SshTransport> { pool.connect(config, SshSecrets(), verify) }
      assertTrue(started.await(2, TimeUnit.SECONDS))
      val other = config.copy(id = "two", host = "two.example")
      val fast = workers.submit<SshTransport> { pool.connect(other, SshSecrets(), verify) }
      fast.get(2, TimeUnit.SECONDS).close()
      workers.submit { pool.invalidate("unrelated") }.get(2, TimeUnit.SECONDS)
      release.countDown()
      slow.get(2, TimeUnit.SECONDS).close()
    } finally {
      release.countDown()
      pool.close()
      workers.shutdownNow()
    }
  }

  @Test fun concurrentUsersOfOneConfigurationShareOneHandshake() {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val calls = AtomicInteger()
    val physical = Transport()
    val pool = SshTransportPool(object : SshTransportFactory {
      override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
        calls.incrementAndGet()
        started.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        return physical
      }
      override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey = error("unused")
    })
    val workers = Executors.newFixedThreadPool(2)
    try {
      val first = workers.submit<SshTransport> { pool.connect(config, SshSecrets(), verify) }
      assertTrue(started.await(2, TimeUnit.SECONDS))
      val second = workers.submit<SshTransport> { pool.connect(config, SshSecrets(), verify) }
      release.countDown()
      val a = first.get(2, TimeUnit.SECONDS)
      val b = second.get(2, TimeUnit.SECONDS)
      assertEquals(1, calls.get())
      a.close()
      assertFalse(physical.isClosed)
      b.close()
      assertTrue(physical.isClosed)
    } finally {
      release.countDown()
      pool.close()
      workers.shutdownNow()
    }
  }

  @Test fun invalidationCannotPublishALateSuccessfulHandshake() {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val calls = AtomicInteger()
    val old = Transport()
    val replacement = Transport()
    val pool = SshTransportPool(object : SshTransportFactory {
      override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
        if (calls.incrementAndGet() == 1) {
          started.countDown()
          check(release.await(5, TimeUnit.SECONDS))
          return old
        }
        return replacement
      }
      override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey = error("unused")
    })
    val workers = Executors.newSingleThreadExecutor()
    try {
      val pending = workers.submit<SshTransport> { pool.connect(config, SshSecrets(), verify) }
      assertTrue(started.await(2, TimeUnit.SECONDS))
      pool.invalidate(config.id)
      val current = pool.connect(config, SshSecrets(), verify)
      release.countDown()
      assertTrue(runCatching { pending.get(2, TimeUnit.SECONDS) }.isFailure)
      assertTrue(old.isClosed)
      assertFalse(current.isClosed)
      current.close()
      assertTrue(replacement.isClosed)
    } finally {
      release.countDown()
      pool.close()
      workers.shutdownNow()
    }
  }
}
