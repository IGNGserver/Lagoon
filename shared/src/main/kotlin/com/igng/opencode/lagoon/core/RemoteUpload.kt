package com.igng.opencode.lagoon.core

/** A file published into the current session's workspace; the prompt references [path] as text. */
internal data class RemoteUploadedFile(val name: String, val path: String, val bytes: Long)
