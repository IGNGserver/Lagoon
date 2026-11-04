package com.igng.opencode.lagoon.core

/** One official plugin bundle, read over the Remote settings channel. */
data class RemotePluginBundle(
  val name: String,
  val enabled: Boolean,
  val installed: Boolean,
  val official: Boolean,
  val version: String?,
  val description: String?,
  val readOnlyReason: String?
)

/** One Loader plugin entry. */
data class RemotePluginEntry(
  val entryId: String,
  val moduleName: String,
  val enabled: Boolean,
  val phase: String?,
  val readOnlyReason: String?
)
