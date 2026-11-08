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

  @Test fun deviceEditorIsAnIndependentFullScreenPage() {
    val source = uiSource("DeviceEditorScreen.kt")
    assertTrue(source.contains("PageTopBar("))
    assertTrue("编辑已有连接与添加设备共用同一整页", source.contains("ProfileEditorPage("))
    assertTrue("SSH 主机表单也是这一页的一层", source.contains("SshHostForm("))
    assertFalse("设备编辑器不再是底部弹窗", source.contains("HyperBottomSheet("))
  }

  /**
   * 设备子树的每一级都必须是路由上的一层：一旦有人把它写回 `detailId` / `showingAddDevice` 这类
   * 页面内部状态，进出场就会重新变回硬切——根 AnimatedContent 只认 NavRoute 的变化。
   */
  @Test fun everyDevicePageIsARouteNotAPageLocalFlag() {
    val list = uiSource("DeviceManagementScreen.kt")
    assertFalse("设备详情不能再用页面内 detailId 切换", list.contains("detailId"))
    assertTrue("列表只负责选择设备，详情由路由渲染", list.contains("onOpenDevice"))
    val activity = uiSource("MainActivity.kt")
    assertFalse("整页编辑器不能再作为根内容之外的布尔浮层", activity.contains("var showingAddDevice"))
    assertFalse("连接编辑不能再退回半屏抽屉", activity.contains("ServersModal("))
    assertTrue("整页编辑器是路由的一层", activity.contains("target.editors.isNotEmpty()"))
    val stack = uiSource("AppBackStack.kt")
    assertTrue(stack.contains("fun openDevice("))
    assertTrue(stack.contains("fun openEditor("))
  }
}
