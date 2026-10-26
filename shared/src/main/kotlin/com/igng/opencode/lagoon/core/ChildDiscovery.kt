package com.igng.opencode.lagoon.core

import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A bounded descendant sweep whose unfinished frontiers survive the next poll. */
internal class ChildDiscovery {
  data class Result(val sessions: List<Session>, val incompleteRoots: Set<String>)

  private class Scan(val activityAt: Long, root: Session) {
    // Insertion order is the breadth-first frontier; leave requests here until they succeed.
    val frontier = linkedMapOf(root.id to root)
    val visited = mutableSetOf<String>()
    val found = linkedMapOf<String, Session>()
  }

  private class ClientState {
    val scans = mutableMapOf<String, Scan>()
    var nextRoot = 0
  }

  private data class Request(val rootId: String, val scan: Scan, val session: Session)

  private val mutex = Mutex()
  // Session ids and cursors must never cross server/client boundaries, even for equal clients.
  private val clients = IdentityHashMap<HarnessClient, ClientState>()

  suspend fun read(client: HarnessClient, roots: List<Session>, parents: Map<String, String>): Result = mutex.withLock {
    val state = clients.getOrPut(client) { ClientState() }
    val identities = roots.filter { it.id.isNotBlank() }.associateBy { it.id }
    state.scans.keys.retainAll(identities.keys)
    identities.forEach { (id, root) ->
      if (state.scans[id]?.activityAt != root.activityAt) state.scans[id] = Scan(root.activityAt, root)
    }
    val rootIds = identities.keys.toList()
    if (rootIds.isEmpty()) return@withLock Result(emptyList(), emptySet())

    val attempted = mutableMapOf<String, MutableSet<String>>()
    var budget = 100
    while (budget > 0) {
      val batch = mutableListOf<Request>()
      repeat(minOf(4, budget)) {
        // Rotate after every request, not every sweep, so wide trees cannot starve other roots.
        for (offset in rootIds.indices) {
          val index = (state.nextRoot + offset) % rootIds.size
          val id = rootIds[index]
          val scan = state.scans.getValue(id)
          val tried = attempted.getOrPut(id) { mutableSetOf() }
          val session = scan.frontier.values.firstOrNull { it.id !in tried } ?: continue
          tried += session.id
          batch += Request(id, scan, session)
          state.nextRoot = (index + 1) % rootIds.size
          break
        }
      }
      if (batch.isEmpty()) break
      budget -= batch.size
      val responses = coroutineScope {
        batch.map { request -> async {
          try {
            kotlin.Result.success(client.children(request.session))
          } catch (cancel: CancellationException) {
            throw cancel
          } catch (error: Exception) {
            kotlin.Result.failure<List<Session>>(error)
          }
        } }.awaitAll()
      }
      batch.zip(responses).forEach { (request, response) ->
        // A failure stays queued, but attempted prevents retrying it in this same read.
        if (response.isSuccess) {
          val scan = request.scan
          scan.frontier.remove(request.session.id)
          scan.visited += request.session.id
          response.getOrThrow().forEach { child ->
            if (child.id.isNotBlank() && child.id != request.rootId) {
              scan.found[child.id] = child
              if (child.id !in scan.visited) scan.frontier.putIfAbsent(child.id, child)
            }
          }
        }
      }
    }

    val found = linkedMapOf<String, Session>()
    val incomplete = mutableSetOf<String>()
    rootIds.forEach { id ->
      val scan = state.scans.getValue(id)
      found.putAll(scan.found)
      if (scan.frontier.isNotEmpty()) incomplete += BackgroundWork.rootOf(id, parents)
      // A completed result is returned once; the following poll freshly validates deletions.
      else state.scans.remove(id)
    }
    Result(found.values.toList(), incomplete)
  }
}
