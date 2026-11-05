package com.igng.opencode.lagoon.core

/** One Host-side automation reminder bound to a Session. */
data class RemoteSchedule(
  val id: String,
  val kind: String,
  val title: String,
  val prompt: String,
  val scheduledAt: String,
  val detail: String
)
