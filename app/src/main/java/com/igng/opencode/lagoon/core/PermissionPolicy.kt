package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Phone-local choices. Neither changes server rules nor answers question forms. */
data class PermissionPolicy(val hide: Boolean = false, val autoApprove: Boolean = false) {
  fun visible(requestId: String, failed: Set<String> = emptySet()): Boolean =
    !hide && (!autoApprove || requestId in failed)

  fun tasks(tasks: Map<String, TaskState>, requests: List<PermissionRequest>, failed: Set<String> = emptySet()): Map<String, TaskState> =
    tasks.mapValues { (session, task) ->
      if (task.phase == TaskPhase.WAITING_PERMISSION && requests.none { it.sessionId == session && visible(it.id, failed) })
        task.copy(phase = TaskPhase.WAITING_SERVER, detail = "等待服务器处理")
      else task
    }
}

/** One approver per server, shared by events, reconnect sweeps and setting changes. */
internal class PermissionAutoApprover(
  private val scope: CoroutineScope,
  private val enabled: () -> Boolean,
  private val reply: suspend (PermissionRequest) -> Unit,
  private val resolved: (PermissionRequest) -> Unit,
  private val changed: (Set<String>) -> Unit = {}
) {
  private val jobs = mutableMapOf<String, Job>()
  private val responded = linkedSetOf<String>()
  private val failures = mutableSetOf<String>()
  private var disposed = false

  fun pending(requests: List<PermissionRequest>): List<PermissionRequest> = requests.filterNot { it.id in responded }

  fun consider(request: PermissionRequest) {
    if (disposed || !enabled() || request.id in responded || request.id in jobs) return
    // Register before launch, including on Main.immediate / Unconfined dispatchers.
    val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
      try {
        for (attempt in 0..2) {
          if (disposed || !enabled()) return@launch
          try {
            reply(request)
            responded += request.id
            while (responded.size > 1000) responded.remove(responded.first())
            failures.remove(request.id)
            resolved(request)
            changed(failures.toSet())
            return@launch
          } catch (cancel: CancellationException) {
            throw cancel
          } catch (error: Exception) {
            if (attempt == 2) {
              failures += request.id
              changed(failures.toSet())
            } else delay(1000L * (attempt + 1))
          }
        }
      } finally {
        jobs.remove(request.id)
      }
    }
    jobs[request.id] = job
    job.start()
  }

  fun settled(id: String) {
    responded += id
    while (responded.size > 1000) responded.remove(responded.first())
    jobs.remove(id)?.cancel()
    if (failures.remove(id)) changed(failures.toSet())
  }

  fun dispose() {
    disposed = true
    jobs.values.toList().forEach(Job::cancel)
    jobs.clear()
  }
}
