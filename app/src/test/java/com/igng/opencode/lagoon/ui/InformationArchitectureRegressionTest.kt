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
    assertFalse("纯 SSH 架构不再存在接入源概念", details.contains("启用接入源"))
    assertFalse("纯 SSH 架构不再存在 Remote Hub", details.contains("Remote Hub"))
    assertFalse("设备详情不得用 hex-only formatFingerprint 破坏 OpenSSH base64 指纹", details.contains("formatFingerprint("))
  }

  @Test fun harnessDetailOwnsPerHarnessConfiguration() {
    val source = uiSource("DeviceManagementScreen.kt")
    assertTrue(source.contains("NewSessionDefaultsCard(state, controller, profile.id)"))
    assertTrue(source.contains("onManageModels(profile.id)"))
    assertTrue(source.contains("主机扩展"))
    assertTrue(source.contains("从此设备移除这个 Harness"))
    assertTrue(source.contains("确认移除"))
    val harness = source.substring(source.indexOf("internal fun HarnessDetailScreen("))
    assertFalse("Harness 详情不能再写设备级开关", harness.contains("controller.setDeviceEnabled"))
    assertFalse("Harness 详情不应提供环状编辑设备 SSH 入口", harness.contains("onEditProfile"))
  }

  @Test fun deviceEditorIsPureSshFullScreenPage() {
    val source = uiSource("DeviceEditorScreen.kt")
    assertTrue(source.contains("PageTopBar("))
    assertTrue("SSH 设备表单直接承载添加与编辑", source.contains("SshHostForm("))
    assertFalse("不再有旧版中枢/直连分叉页", source.contains("AddDevicePage("))
    assertFalse("不再有兼容直连表单", source.contains("MiuixServerForm("))
    assertFalse("设备编辑器不再是底部弹窗", source.contains("HyperBottomSheet("))
  }

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
    assertTrue("contentKey 必须区分设备与 Harness", activity.contains("target.harness != null -> \"harness:"))
    assertTrue("onSaveInstanceState 必须保留 parentPages", activity.contains("KEY_NAV_PARENT_PAGES"))
    val stack = uiSource("AppBackStack.kt")
    assertTrue(stack.contains("fun openDevice("))
    assertTrue(stack.contains("fun openHarness("))
    assertTrue(stack.contains("fun openEditor("))
    assertTrue("返回一次只退一层：Harness → 设备 → 页面", stack.contains("harness != null -> copy(harness = null)"))
  }
}
