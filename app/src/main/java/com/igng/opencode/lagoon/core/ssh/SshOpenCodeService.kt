package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.openCodeMajorVersion
import com.igng.opencode.lagoon.core.OPENCODE_MIN_MAJOR_VERSION
import java.util.concurrent.CancellationException

/** One explicit start/restart of the user's own OpenCode background service. */
internal data class OpenCodeServiceResult(
  val installed: Boolean,
  val action: String,
  val version: String?,
  val previousState: String?,
  val exitCode: Int?,
  val output: List<String>
) {
  val ok: Boolean get() = installed && exitCode == 0
  val actionLabel: String get() = if (action == "restart") "已重启" else "已启动"

  /** The running major version supports the native HTTP/SSE contract this client speaks. */
  val versionSupported: Boolean
    get() = openCodeMajorVersion(version)?.let { it >= OPENCODE_MIN_MAJOR_VERSION } ?: true

  fun summary(): String = when {
    !installed -> "设备上没有找到 opencode 命令，请先安装 OpenCode 2.x"
    !versionSupported -> "设备上的 OpenCode 是 ${version ?: "未知版本"}，需要 2.x 才能使用原生连接"
    exitCode != 0 -> "opencode service $action 失败（退出码 $exitCode）" + output.lastOrNull()?.let { "：$it" }.orEmpty()
    else -> "OpenCode $actionLabel" + version?.let { " · $it" }.orEmpty()
  }
}

/**
 * Starts or restarts the server the user already owns. This is deliberately separate from
 * discovery: nothing here runs during a probe, and the caller must have asked for it explicitly.
 * Only fixed, app-controlled commands run; no field of the registration or the profile is
 * interpolated into the script.
 */
internal class SshOpenCodeService(private val transport: SshTransport) {

  fun restart(): OpenCodeServiceResult {
    val result = try {
      transport.exec(SCRIPT, 45_000)
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (_: Exception) {
      throw SshFailure(SshFailureKind.OTHER, "无法在设备上运行 OpenCode 服务命令", retryable = true)
    }
    if (result.stdout.length > 64 * 1024) throw SshFailure(SshFailureKind.OTHER, "OpenCode 服务命令输出异常", retryable = true)
    val lines = result.stdout.lineSequence().toList()
    fun tagged(prefix: String): String? = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()
    val installed = tagged("LAGOON_OC_INSTALLED ")?.let { it == "1" }
      ?: throw SshFailure(SshFailureKind.OTHER, "OpenCode 服务命令没有返回结果", retryable = true)
    val output = lines.filter { it.startsWith("LAGOON_OC_LINE ") }.map { it.removePrefix("LAGOON_OC_LINE ").take(240) }
    if (!installed) return OpenCodeServiceResult(false, "start", null, null, result.exitCode, output)
    return OpenCodeServiceResult(
      installed = true,
      action = tagged("LAGOON_OC_ACTION ")?.takeIf { it == "start" || it == "restart" } ?: "start",
      version = tagged("LAGOON_OC_VERSION ")?.takeIf { it.isNotBlank() }?.take(200),
      previousState = tagged("LAGOON_OC_STATE ")?.takeIf { it.isNotBlank() }?.take(120),
      exitCode = tagged("LAGOON_OC_EXIT ")?.toIntOrNull() ?: result.exitCode,
      output = output
    )
  }

  internal companion object {
    /** `service status` decides between start and restart; both print the URL the service now uses. */
    internal val SCRIPT: String = SshExecutableSearch.prologue + "\n" + """
      if ! command -v opencode >/dev/null 2>&1; then
        printf '%s\n' 'LAGOON_OC_INSTALLED 0'
        exit 0
      fi
      printf '%s\n' 'LAGOON_OC_INSTALLED 1'
      §__lagoon_version=$(opencode --version 2>/dev/null | head -n 1 | tr -d '\r')
      printf 'LAGOON_OC_VERSION %s\n' "§__lagoon_version"
      §__lagoon_state=$(opencode service status 2>/dev/null | head -n 1 | tr -d '\r')
      printf 'LAGOON_OC_STATE %s\n' "§__lagoon_state"
      case "§__lagoon_state" in
        *[Rr]unning*) §__lagoon_action=restart ;;
        *) §__lagoon_action=start ;;
      esac
      printf 'LAGOON_OC_ACTION %s\n' "§__lagoon_action"
      §__lagoon_out=$(opencode service "§__lagoon_action" 2>&1)
      §__lagoon_code=§?
      printf 'LAGOON_OC_EXIT %s\n' "§__lagoon_code"
      printf '%s\n' "§__lagoon_out" | head -n 5 | while IFS= read -r §__lagoon_line; do
        printf 'LAGOON_OC_LINE %s\n' "$(printf '%s' "§__lagoon_line" | tr -d '\000-\010\013\014\016-\037')"
      done
    """.trimIndent().replace('§', '$')
  }
}
