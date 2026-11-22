package com.igng.opencode.lagoon.core

/** One official DSH plugin bundle. */
data class DshPluginBundle(
  val name: String,
  val enabled: Boolean,
  val installed: Boolean,
  val official: Boolean,
  val version: String?,
  val description: String?,
  val readOnlyReason: String?
)

/** One DSH Loader plugin entry. */
data class DshPluginEntry(
  val entryId: String,
  val moduleName: String,
  val enabled: Boolean,
  val phase: String?,
  val readOnlyReason: String?
)
