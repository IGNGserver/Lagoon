package com.igng.opencode.lagoon.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 生物识别入口的源码级回归测试。
 *
 * `BiometricManager.canAuthenticate()` 会经 binder 校验 `USE_BIOMETRIC`，清单缺权限时抛
 * `SecurityException`；SshKeyGenerateDialog / SshKeyImportDialog 在组合首帧读取
 * `isBiometricSupported()`，异常会直接杀掉进程（点「导入私钥」「生成新密钥」闪退）。
 * JVM 单测起不了 Android 框架，这里守住两条线：清单必须声明权限，`canAuthenticate` 必须包在
 * `runCatching` 内、查询失败降级为「不支持」。
 */
class BiometricSupportRegressionTest {
  private fun source(relative: String): String {
    val candidates = listOf(File(relative), File("../$relative"))
    return candidates.firstOrNull { it.isFile }?.readText()
      ?: error("找不到源文件：$relative（工作目录 ${File(".").absolutePath}）")
  }

  @Test
  fun manifestDeclaresBiometricPermissions() {
    val manifest = source("app/src/main/AndroidManifest.xml")
    assertTrue(
      "AndroidManifest 缺少 USE_BIOMETRIC：BiometricManager.canAuthenticate() 会抛 SecurityException，" +
        "密钥库对话框在组合期读取生物识别能力时会直接闪退",
      "android.permission.USE_BIOMETRIC" in manifest
    )
    assertTrue(
      "AndroidManifest 缺少 USE_FINGERPRINT：API 28 的 BiometricPrompt 仍校验旧指纹权限",
      "android.permission.USE_FINGERPRINT" in manifest
    )
  }

  @Test
  fun canAuthenticateFailureDegradesToUnsupported() {
    val text = source("app/src/main/java/com/igng/opencode/lagoon/core/BiometricKeyVault.kt")
    val function = text.substringAfter("fun isSupported(context: Context): Boolean {")
      .substringBefore("private fun aliasFor")
    // 注释里也出现 canAuthenticate()，只匹配真正的调用点，避免把说明文字当成实现。
    val guard = function.indexOf("val status = runCatching")
    val canAuthenticate = function.indexOf("manager.canAuthenticate(")
    val fallback = function.indexOf("getOrNull() ?: return false")
    assertTrue("isSupported() 里已找不到 canAuthenticate 调用，测试需要同步更新", canAuthenticate >= 0)
    assertTrue(
      "canAuthenticate() 必须在 runCatching 内：权限或 ROM 策略拒绝时不能把组合中的界面带崩",
      guard in 0 until canAuthenticate
    )
    assertTrue(
      "canAuthenticate() 查询失败必须降级为「不支持生物识别」（getOrNull() ?: return false）",
      fallback > canAuthenticate
    )
  }
}
