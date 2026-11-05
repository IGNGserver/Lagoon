package com.igng.opencode.lagoon.core

/** Non-secret state of one Host credential reference. */
data class RemoteCredentialStatus(
  val ref: String,
  val configured: Boolean,
  val writable: Boolean,
  val source: String?
)
