package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 取消异常曾被当成业务错误展示（“StandaloneCoroutine was cancelled”）；本应用自己的中文文案与
 * 服务端契约消息必须原样保留，平台英文异常则翻译成可读提示。
 */
class UserFacingErrorTest {
  @Test fun cancellationIsNeverShown() {
    assertEquals("刷新失败", userFacingError(CancellationException("StandaloneCoroutine was cancelled"), "刷新失败"))
    assertEquals("操作失败", userFacingError(CancellationException(null)))
  }

  @Test fun serverContractMessagesPassThroughEvenInEnglish() {
    assertEquals("用户名或密码错误", userFacingError(ApiException(401, "用户名或密码错误")))
    assertEquals("Authentication required", userFacingError(ApiException(401, "Authentication required")))
  }

  @Test fun ourOwnMessagesPassThrough() {
    assertEquals(
      "HTTP 明文连接未获授权，请在服务器资料中明确开启",
      userFacingError(IllegalArgumentException("HTTP 明文连接未获授权，请在服务器资料中明确开启"))
    )
  }

  @Test fun platformMessagesAreMappedInsteadOfLeaked() {
    assertEquals("找不到服务器地址，请检查网络或地址", userFacingError(UnknownHostException("api.example.invalid")))
    assertEquals("连接服务器超时，请稍后重试", userFacingError(SocketTimeoutException("timeout")))
    assertEquals("操作失败", userFacingError(IOException("unexpected end of stream")))
    assertEquals("刷新失败", userFacingError(RuntimeException(), "刷新失败"))
  }
}
