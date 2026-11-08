package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Structural ownership guards for code that cannot be composed in local JVM tests. */
class InformationArchitectureRegressionTest {
  private fun uiSource(name: String): String {
    val candidates = listOf(
      File("app/src/main/java/com/igng/opencode/lagoon/ui/$name"),
      File("src/main/java/com/igng/opencode/lagoon/ui/$name")
    )
    return candidates.firstOrNull { it.isFile }?.readText() ?: error("找不到 UI 源文件：$name")
  }

  @Test fun globalSettingsDoNotRegainPerDeviceConfiguration() {
    val source = uiSource("Screens.kt")
    val start = source.indexOf("fun SettingsScreen(")
    val end = source.indexOf("private fun UpdateControls(", start)
    assertTrue(start >= 0 && end > start)
    val settings = source.substring(start, end)
    listOf("NewSessionDefaultsCard", "ModelManagementSheet", "RemoteAccountSheet", "onManageModels", "onManageHub", "onAddDevice")
      .forEach { assertFalse("设备级配置不属于全局设置：$it", settings.contains(it)) }
    assertTrue(settings.contains("onManageDevices"))
    assertTrue(settings.contains("权限请求处理"))
    assertTrue(settings.contains("关于与诊断"))
  }

  @Test fun deviceDetailsOwnDefaultsConnectionAndRemoteManagement() {
    val details = uiSource("DeviceManagementScreen.kt")
    assertTrue(details.contains("NewSessionDefaultsCard(state, controller, profile.id)"))
    assertTrue(details.contains("onManageModels"))
    assertFalse(details.contains("selectProfile("))
    assertTrue(details.contains("onEditProfile"))
    assertTrue(details.contains("onManageHub"))
    assertTrue(details.contains("主机扩展"))
    assertTrue(details.contains("删除 ${'$'}{profile.name}？"))
    assertTrue(details.contains("确认删除"))
  }

  @Test fun addDeviceIsAnIndependentFullScreenFlow() {
    val source = uiSource("AddDeviceSheet.kt")
    assertTrue(source.contains("BackHandler(onBack = ::back)"))
    assertTrue(source.contains("PageTopBar("))
    assertFalse("添加设备不再应该重新包成底部弹窗", source.contains("HyperBottomSheet("))
  }
}
