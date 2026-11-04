package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class RemoteFailureTest {
  @Test fun controlPlaneStatusesMapToActionableKinds() {
    assertEquals(RemoteFailureKind.AUTH_REQUIRED, remoteServerFailure(RemoteServerException(401, null, false)).kind)
    assertEquals(RemoteFailureKind.REJECTED, remoteServerFailure(RemoteServerException(403, null, false)).kind)
    assertEquals(RemoteFailureKind.HOST_NOT_FOUND, remoteServerFailure(RemoteServerException(404, null, false)).kind)
    assertEquals(RemoteFailureKind.RATE_LIMITED, remoteServerFailure(RemoteServerException(429, null, true)).kind)
    assertEquals(RemoteFailureKind.NETWORK, remoteServerFailure(RemoteServerException(503, null, true)).kind)
  }

  @Test fun trustFailuresStayDistinct() {
    val required = RemoteFailureException(RemoteFailureKind.TRUST_REQUIRED, "尚未确认")
    val changed = RemoteFailureException(RemoteFailureKind.TRUST_CHANGED, "指纹变化")
    assertEquals("核对指纹", required.actionLabel)
    assertEquals(false, required.retryable)
    assertEquals("核对指纹", changed.actionLabel)
    assertSame(changed, remoteFailure(RuntimeException(changed)))
  }

  @Test fun offlineIsRetryable() {
    val offline = RemoteFailureException(RemoteFailureKind.OFFLINE, "主机现在不在线", retryable = true)
    assertTrue(offline.retryable)
    assertEquals("重试", offline.actionLabel)
  }
}
