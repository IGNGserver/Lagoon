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

  @Test fun deviceDetailOwnsTheDeviceSwitchAndItsHarnessList() {
    val details = uiSource("DeviceManagementScreen.kt")
    assertTrue("设备页必须有设备级开关", details.contains("启用此设备"))
    assertTrue("设备页是唯一挂载 Harness 的地方", details.contains("onAddHarness"))
    assertTrue("设备页只读缓存，刷新是显式动作", details.contains("onRefreshDiscovery"))
    assertTrue("Harness 合并进设备页", details.contains("fun HarnessRow("))
    assertFalse("设备页不再平铺接入源开关", details.contains("启用接入源"))
    assertFalse("设备数不能再用 leaf 数量冒充", details.contains("profiles.size} 个 Harness"))
  }

  @Test fun harnessDetailOwnsPerHarnessConfiguration() {
    val source = uiSource("DeviceManagementScreen.kt")
    assertTrue(source.contains("NewSessionDefaultsCard(state, controller, profile.id)"))
    assertTrue(source.contains("onManageModels"))
    assertTrue(source.contains("主机扩展"))
    assertTrue(source.contains("从此设备移除这个 Harness"))
    assertTrue(source.contains("确认移除"))
    val harness = source.substring(source.indexOf("internal fun HarnessDetailScreen("))
    assertFalse("Harness 详情不能再写设备级开关", harness.contains("controller.setDeviceEnabled"))
  }

  @Test fun deviceEditorIsAnIndependentFullScreenPage() {
    val source = uiSource("DeviceEditorScreen.kt")
    assertTrue(source.contains("PageTopBar("))
    assertTrue("添加设备是同一种页面的两个分支", source.contains("AddDevicePage("))
    assertTrue("SSH 设备表单也是这一页的一层", source.contains("SshHostForm("))
    assertFalse("设备编辑器不再是底部弹窗", source.contains("HyperBottomSheet("))
    assertFalse("添加 SSH 设备时不再预选 Harness", source.contains("useDsh"))
  }

  /**
   * 设备子树的每一级都必须是路由上的一层：一旦有人把它写回 `detailId` / `showingAddDevice` 这类
   * 页面内部状态，进出场就会重新变回硬切——根 AnimatedContent 只认 NavRoute 的变化。
   */
  @Test fun everyDevicePageIsARouteNotAPageLocalFlag() {
    val list = uiSource("DeviceManagementScreen.kt")
    assertFalse("设备详情不能再用页面内 detailId 切换", list.contains("detailId"))
    assertTrue("列表只负责选择设备，详情由路由渲染", list.contains("onOpenDevice"))
    assertTrue("Harness 详情是设备详情之上的一层", list.contains("onOpenHarness"))
    val activity = uiSource("MainActivity.kt")
    assertFalse("整页编辑器不能再作为根内容之外的布尔浮层", activity.contains("var showingAddDevice"))
    assertFalse("连接编辑不能再退回半屏抽屉", activity.contains("ServersModal("))
    assertTrue("整页编辑器是路由的一层", activity.contains("target.editors.isNotEmpty()"))
    assertTrue("Harness 详情由路由渲染", activity.contains("target.harness != null"))
    val stack = uiSource("AppBackStack.kt")
    assertTrue(stack.contains("fun openDevice("))
    assertTrue(stack.contains("fun openHarness("))
    assertTrue(stack.contains("fun openEditor("))
    assertTrue("返回一次只退一层：Harness → 设备 → 页面", stack.contains("harness != null -> copy(harness = null)"))
  }
}
