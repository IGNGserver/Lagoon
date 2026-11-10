package com.igng.opencode.lagoon.core

/** Contract errors are not capability absence. Never change the meaning of an admitted write. */
internal val ApiException.isDshMethodUnavailable: Boolean
  get() = code in setOf("gateway/service-unavailable", "gateway/method-unavailable", "gateway/lookup-unavailable", "METHOD_NOT_FOUND", "FEATURE_NOT_SUPPORTED") ||
    (status == 404 && (code == null || code == "http/404"))
