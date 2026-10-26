package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Admission is not execution: an accepted inbox item can outlive the previous run's outcome. */
internal object PendingInputs {
  data class Read(val pending: Set<String> = emptySet(), val verified: Set<String> = emptySet())

  suspend fun read(client: HarnessClient, sessions: List<Session>, tasks: Map<String, TaskState>, active: Set<String>?): Read {
    if (active == null) return Read()
    val identities = sessions.map { it.id }.toSet()
    val candidates = tasks.values.filter { it.active && it.sessionId !in active && it.sessionId in identities }
      .sortedByDescending { it.pendingInput }.take(100)
    val pending = mutableSetOf<String>()
    val verified = mutableSetOf<String>()
    candidates.chunked(4).forEach { batch ->
      coroutineScope { batch.map { task -> async {
        task.sessionId to try { Result.success(client.hasPendingInput(task.sessionId)) }
          catch (cancel: CancellationException) { throw cancel }
          catch (error: Exception) { Result.failure(error) }
      } }.awaitAll() }.forEach { (id, result) ->
        if (result.isSuccess) {
          verified += id
          if (result.getOrThrow()) pending += id
        }
      }
    }
    return Read(pending, verified)
  }

  fun accepted(id: String, previous: TaskState?, idle: Long, now: Long = System.currentTimeMillis()): TaskState {
    val executing = previous?.active == true && !previous.background && !previous.uncertain && previous.phase in TaskState.RUNNING_PHASES
    return if (executing) previous!!.copy(pendingInput = true)
    else TaskState(id, TaskPhase.WAITING_SERVER, "等待服务器处理", since = now, activeAt = idle, pendingInput = true)
  }
}
