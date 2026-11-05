package com.igng.opencode.lagoon.core

/** Locally rendered bytes for a read-only file preview; never the file's authoritative copy. */
data class FilePreview(val mime: String, val bytes: ByteArray)
