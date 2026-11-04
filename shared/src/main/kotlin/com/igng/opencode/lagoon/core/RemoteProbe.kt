package com.igng.opencode.lagoon.core

/** A single encrypted RPC round trip; not a network-layer RTT. */
data class RemoteProbe(val durationMillis: Long)
