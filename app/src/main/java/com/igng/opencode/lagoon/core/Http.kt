package com.igng.opencode.lagoon.core

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit

/**
 * Process-wide OkHttp resources.
 *
 * OkHttp creates a ConnectionPool (up to 5 sockets plus a cleanup thread) and a Dispatcher (executor
 * threads) per client instance. The app used to build four unrelated clients, so API calls, the SSE
 * stream and pair-link resolution could not reuse TCP/TLS connections and each kept
 * its own idle sockets. Sharing the pool lets every request after the SSE stream is open reuse the
 * already-authenticated connection instead of paying a fresh handshake, and sharing the dispatcher
 * avoids redundant idle thread pools.
 */
internal object SharedHttp {
  val connectionPool = ConnectionPool(maxIdleConnections = 8, keepAliveDuration = 5, timeUnit = TimeUnit.MINUTES)
  // A connect fans out per session/directory (permissions, forms, shells, children). OkHttp's default of
  // five requests per host turned an HTTP/1.1 fan-out of 20 reads into four serial rounds.
  val dispatcher = Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST }
  // Long-lived SSE calls must not occupy the HTTP request slots (five per host by default),
  // especially when several saved endpoints share one reverse-proxy hostname.
  val streamDispatcher = Dispatcher().apply {
    maxRequests = Int.MAX_VALUE
    maxRequestsPerHost = Int.MAX_VALUE
  }
  const val MAX_REQUESTS_PER_HOST = 8
  /** Blocking HTTP work; shared so per-API instances do not each leak a scope. */
  val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
