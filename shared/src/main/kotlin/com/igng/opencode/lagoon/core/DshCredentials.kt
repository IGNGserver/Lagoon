package com.igng.opencode.lagoon.core

/** Non-secret state of one DSH Host credential reference. */
data class DshCredentialStatus(
  val ref: String,
  val configured: Boolean,
  val writable: Boolean,
  val source: String?
)
