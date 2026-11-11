package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class RemoteFailureTest {
  @Test fun controlPlaneStatusesMapToActionableKinds() {
    assertEquals(RemoteFailureKind.AUTH_REQUIRED, remoteServerFailure(RemoteServerException(401, "AUTH_INVALID", false)).kind)
    assertEquals(RemoteFailureKind.REJECTED, remoteServerFailure(RemoteServerException(403, "MEMBERSHIP_REQUIRED", false)).kind)
    for (status in listOf(401, 403)) {
      val unknown = remoteServerFailure(RemoteServerException(status, null, false))
      assertEquals(RemoteFailureKind.NETWORK, unknown.kind)
      assertTrue(unknown.retryable)
    }
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

  @Test fun recoveryActionsAreTypedNotMessageParsed() {
    assertEquals(RemoteRecoveryKind.LOGIN, RemoteFailureKind.AUTH_REQUIRED.recovery())
    assertEquals(RemoteRecoveryKind.LOGIN, RemoteFailureKind.DEVICE_REVOKED.recovery())
    assertEquals(RemoteRecoveryKind.TRUST, RemoteFailureKind.TRUST_CHANGED.recovery())
    assertEquals(RemoteRecoveryKind.HOST, RemoteFailureKind.HOST_NOT_FOUND.recovery())
    assertEquals(RemoteRecoveryKind.RECONNECT, RemoteFailureKind.CONNECTION_REPLACED.recovery())
    assertNull(RemoteFailureKind.OFFLINE.recovery())
  }

  @Test fun relayBackpressureKeepsItsUpstreamCodeAndIsRetryable() {
    val failure = remoteControlFailure(RemoteControlException("SLOW_CONSUMER", "Relay consumer is too slow", true))
    assertEquals(RemoteFailureKind.SLOW_CONSUMER, failure.kind)
    assertTrue(failure.retryable)
    assertEquals(RemoteRecoveryKind.RECONNECT, failure.kind.recovery())
  }
  @Test fun offlineIsRetryable() {
    val offline = RemoteFailureException(RemoteFailureKind.OFFLINE, "主机现在不在线", retryable = true)
    assertTrue(offline.retryable)
    assertEquals("重试", offline.actionLabel)
  }
}
