package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A dead SSH tunnel or WebSocket path must be retried, not shown as a permanent host contract failure. */
class TransportFailureTest {
  @Test fun sshAndStreamDropsAreRetryable() {
    assertTrue(IOException("transfer closed").isRetryableTransportFailure())
    assertTrue(IOException("unexpected end of stream").isRetryableTransportFailure())
    assertTrue(SshFailure(SshFailureKind.UNREACHABLE, "SSH 连接断开", retryable = true).isRetryableTransportFailure())
    assertTrue(ApiException(0, "与主机的连接中断，正在重连").isRetryableTransportFailure())
    assertTrue(ApiException(502, "transfer closed", "transport_closed").isRetryableTransportFailure())
  }

  @Test fun hostContractAndAuthRejectionsStayNonRetryable() {
    assertFalse(ApiException(400, "参数错误").isRetryableTransportFailure())
    assertFalse(ApiException(502, "gateway/denied", "gateway/denied").isRetryableTransportFailure())
    assertFalse(SshFailure(SshFailureKind.AUTH, "SSH 认证失败", retryable = false).isRetryableTransportFailure())
    assertFalse(SshFailure(SshFailureKind.HOST_KEY_CHANGED, "主机指纹已变化", retryable = false).isRetryableTransportFailure())
  }

  @Test fun libraryTransportTextNeverReachesTheToast() {
    assertEquals("刷新失败", userFacingError(IOException("transfer closed"), "刷新失败"))
    assertEquals("刷新失败", userFacingError(ApiException(502, "transfer closed"), "刷新失败"))
    // Ordinary contract text still passes through unchanged.
    assertEquals("用户名或密码错误", userFacingError(ApiException(401, "用户名或密码错误")))
  }
}
