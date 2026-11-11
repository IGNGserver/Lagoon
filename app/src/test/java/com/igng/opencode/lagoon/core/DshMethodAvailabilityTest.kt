package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class DshMethodAvailabilityTest {
  @Test fun invalidArgumentsAndPermissionErrorsNeverMasqueradeAsMissingFeatures() {
    for (code in listOf("gateway/arguments-invalid", "gateway/bad-request", "gateway/denied", "workspace-file/not-found", "METHOD_NOT_ALLOWED")) {
      assertFalse(code, ApiException(502, "failure", code).isDshMethodUnavailable)
    }
    assertFalse(ApiException(404, "file missing", "workspace-file/not-found").isDshMethodUnavailable)
    assertTrue(ApiException(502, "missing", "gateway/service-unavailable").isDshMethodUnavailable)
    assertTrue(ApiException(404, "route missing", "http/404").isDshMethodUnavailable)
  }
}
