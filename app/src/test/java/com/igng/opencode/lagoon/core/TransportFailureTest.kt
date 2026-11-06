package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A dead relay/RTC path must be retried, not shown as a permanent host contract failure. */
class TransportFailureTest {
  @Test fun relayAndRtcDropsAreRetryable() {
    assertTrue(IOException("remote rtc失败 transfer closed").isRetryableTransportFailure())
    assertTrue(IOException("unexpected end of stream").isRetryableTransportFailure())
    assertTrue(RemoteRpcException("TRANSPORT_CLOSED").isRetryableTransportFailure())
    assertTrue(RemoteGatewayException("remote/rtc-failed", "transfer closed").isRetryableTransportFailure())
    assertTrue(RemoteFailureException(RemoteFailureKind.NETWORK, "中继断开", retryable = true).isRetryableTransportFailure())
    assertTrue(ApiException(0, "与主机的连接中断，正在重连").isRetryableTransportFailure())
    assertTrue(ApiException(502, "remote rtc失败 transfer closed", "remote/rtc-failed").isRetryableTransportFailure())
  }

  @Test fun hostContractRejectionsStayNonRetryable() {
    assertFalse(ApiException(400, "参数错误").isRetryableTransportFailure())
    assertFalse(ApiException(502, "gateway/denied", "gateway/denied").isRetryableTransportFailure())
    assertFalse(RemoteGatewayException("gateway/arguments-invalid", "bad request").isRetryableTransportFailure())
    assertFalse(RemoteFailureException(RemoteFailureKind.AUTH_REQUIRED, "请重新登录").isRetryableTransportFailure())
  }

  @Test fun libraryTransportTextNeverReachesTheToast() {
    assertEquals("刷新失败", userFacingError(IOException("remote rtc失败 transfer closed"), "刷新失败"))
    assertEquals("刷新失败", userFacingError(ApiException(502, "remote rtc失败 transfer closed"), "刷新失败"))
    // Ordinary contract text still passes through unchanged.
    assertEquals("用户名或密码错误", userFacingError(ApiException(401, "用户名或密码错误")))
  }
}
