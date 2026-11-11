package com.igng.opencode.lagoon.core

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import org.json.JSONArray
import org.json.JSONObject

/** Owns transport resources; the registry remains authoritative for local lifecycle and routing. */
internal class DshQuestionCoordinator(
  private val scope: CoroutineScope,
  private val clock: () -> Long,
  private val openClaim: suspend (String, String, String, (Long) -> Unit, () -> Unit) -> Closeable,
  private val settle: suspend (String, JSONObject) -> Unit,
  private val answer: suspend (String, String, JSONObject) -> Unit,
  private val refresh: suspend (String) -> Unit,
  private val publish: (List<DshQuestionRegistry.Snapshot>) -> Unit,
  private val onError: (Throwable) -> Unit = {}
) : Closeable {
  private class Claim { var job: Job? = null; var handle: Closeable? = null; var opened = false }
  private val lock = Any()
  private val registry = DshQuestionRegistry()
  private val claims = mutableMapOf<String, Claim>()
  private val settlements = mutableSetOf<Job>()
  private val terminal = boundedSet()
  private val completed = boundedSet()
  private val mutating = mutableSetOf<String>()
  private val knownInbox = mutableSetOf<String>()
  private var published = ""
  private var session: String? = null
  private var foreground = false
  private var closed = false
  private val ticker: Job = scope.launch(start = CoroutineStart.LAZY) { try { while (isActive) { delay(100); tick() } } finally { close() } }
  init { ticker.start() }

  fun receive(sessionId: String, eventId: String, request: JSONObject): String = synchronized(lock) {
    check(!closed) { "Question coordinator is closed" }
    if (eventId in terminal) return@synchronized request.optJSONObject("wait")?.optString("callId")?.let { registry.idFor(sessionId, it) }
      ?: "dsh:question-event:${sessionId.length}:$sessionId:${eventId.length}:$eventId"
    val id = registry.receiveWaterfall(sessionId, eventId, request)
    completed.remove(id)
    updatePresentation(id, sessionId)
    emit(); claimVisible()
    id
  }

  /** values is the projection values object, containing userQuestions and (when loaded) inbox. */
  fun reconcile(sessionId: String, values: JSONObject) = synchronized(lock) {
    if (closed) return@synchronized
    registry.reconcile(sessionId, values.optJSONObject("userQuestions"), values.optJSONObject("inbox"))
    if (values.optJSONObject("userQuestions") != null) {
      if (values.optJSONObject("inbox") != null) {
        knownInbox += sessionId
        // A queued reply can be discarded before admission. Local success is not permanent
        // settlement: an authoritative continued+unqueued call must become answerable again.
        registry.snapshots(clock()).filter { it.sessionId == sessionId && it.state == "continued" && !it.queued }
          .forEach { completed.remove(it.id) }
      } else knownInbox -= sessionId
    }
    registry.snapshots(clock()).filter { it.sessionId == sessionId }.forEach { updatePresentation(it.id, sessionId) }
    emit(); claimVisible()
  }

  fun presentation(sessionId: String?, foreground: Boolean) = synchronized(lock) {
    if (closed) return@synchronized
    session = sessionId; this.foreground = foreground
    registry.snapshots(clock()).forEach { updatePresentation(it.id, it.sessionId) }
    emit(); claimVisible()
  }
  fun focus(id: String, focused: Boolean) = synchronized(lock) { if (!closed) { registry.focus(id, focused, clock()); emit() } }
  fun engage(id: String) = synchronized(lock) { if (!closed) { registry.engage(id, clock()); emit() } }
  fun takeTime(id: String) = synchronized(lock) { if (!closed) { registry.takeTime(id); emit() } }
  fun tick() = synchronized(lock) { if (!closed) { dispatch(registry.tick(clock())); emit() } }
  fun snapshots(): List<DshQuestionRegistry.Snapshot> = synchronized(lock) {
    registry.snapshots(clock()).filter { it.route != DshQuestionRegistry.Route.UNAVAILABLE &&
      (it.route != DshQuestionRegistry.Route.CONTINUED || it.sessionId in knownInbox) && it.id !in completed && it.id !in mutating }
  }
  fun snapshotForCall(sessionId: String, callId: String): DshQuestionRegistry.Snapshot? = synchronized(lock) {
    registry.snapshots(clock()).firstOrNull { it.sessionId == sessionId && it.callId == callId }
  }
  fun currentEvent(sessionId: String, callId: String): String? =
    snapshotForCall(sessionId, callId)?.eventId

  fun forms(directory: (String) -> String = { "" }): List<QuestionRequest> = snapshots().map { it.toQuestionForm(directory(it.sessionId)) }

  suspend fun answer(id: String, value: JSONObject) = submit(id, value, dismiss = false)
  suspend fun dismiss(id: String) = submit(id, JSONObject(), dismiss = true)

  private suspend fun submit(id: String, value: JSONObject, dismiss: Boolean) {
    val target = synchronized(lock) {
      check(!closed) { "Question coordinator is closed" }
      val current = snapshots().firstOrNull { it.id == id } ?: throw ApiException(409, "这次提问已结束或正在处理")
      if (dismiss && current.route != DshQuestionRegistry.Route.LIVE) throw ApiException(409, "继续回答需要提交答案")
      current.eventId?.let { if (!terminal.add(it)) throw ApiException(409, "回答结果尚未确认，请刷新") }
      mutating += id
      knownInbox -= current.sessionId
      if (current.callId != null) current.eventId?.let { registry.cancelWaterfall(it) }
      emit()
      current
    }
    var refreshStarted = false
    try {
      if (target.route == DshQuestionRegistry.Route.LIVE) {
        val outcome = if (dismiss) rejected("ASK_CANCELLED", "用户取消了回答") else JSONObject().put("kind", "result").put("value", value)
        settle(requireNotNull(target.eventId), outcome)
      } else answer(target.sessionId, requireNotNull(target.callId), value)
      refreshStarted = true
      if (target.callId != null) refresh(target.sessionId)
      synchronized(lock) {
        val current = registry.snapshot(id, clock())
        if (!closed && target.route == DshQuestionRegistry.Route.LIVE && !dismiss && current?.route == DshQuestionRegistry.Route.CONTINUED) {
          throw ApiException(409, "提问已转为继续回答，请确认后重新提交")
        }
        target.eventId?.let { registry.cancelWaterfall(it) }
        // A newer delivery for the same stable call ID may arrive while this mutation is suspended.
        // Never let completion of the old event hide that replacement live card.
        if (current?.eventId == null || current.eventId == target.eventId) completed += id
      }
    } catch (error: CancellationException) {
      if (target.callId != null) scope.launch { refreshSafely(target.sessionId) }
      throw error
    } catch (error: Exception) {
      // The mutation may have reached the Host. Reconcile once, never retry or change routes here.
      if (!refreshStarted && target.callId != null) try { refresh(target.sessionId) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
      throw error
    } finally {
      synchronized(lock) {
        target.eventId?.let(::release)
        mutating -= id
        if (!closed) { emit(); claimVisible() }
      }
    }
  }

  fun cancelled(eventId: String) = synchronized(lock) {
    if (closed) return@synchronized
    terminal += eventId
    registry.cancelWaterfall(eventId); release(eventId); emit()
  }

  override fun close() = synchronized(lock) {
    if (closed) return@synchronized
    closed = true
    ticker.cancel()
    val actions = registry.close()
    claims.keys.toList().forEach(::release)
    dispatch(actions)
    publish(emptyList())
  }

  /** The enclosing transport must remain alive until delegated gateway outcomes finish. */
  fun hasPendingSettlements(): Boolean = synchronized(lock) { settlements.isNotEmpty() }
  suspend fun awaitSettlements() {
    while (true) {
      val pending = synchronized(lock) { settlements.toList() }
      if (pending.isEmpty()) return
      pending.joinAll()
    }
  }

  fun abortSettlements() = synchronized(lock) {
    settlements.toList().forEach { it.cancel() }
    claims.keys.toList().forEach(::release)
  }

  private fun updatePresentation(id: String, sessionId: String) {
    dispatch(registry.setPresentation(id, sessionId == session, foreground))
  }
  private fun emit() {
    val values = snapshots()
    val signature = values.joinToString { "${it.copy(questions = JSONArray())}:${it.questions}" }
    if (signature != published) { published = signature; publish(values) }
  }
  private fun release(event: String) {
    val claim = claims.remove(event) ?: return
    claim.job?.cancel()
    runCatching { claim.handle?.close() }.onFailure(onError)
  }
  private fun dispatch(actions: List<DshQuestionRegistry.Action>) {
    actions.groupBy { it.eventId }.forEach { (eventId, related) ->
      val action = related.firstOrNull { it.kind != DshQuestionRegistry.ActionKind.RELEASE_CLAIM }
      if (action == null) {
        release(eventId)
      } else if (terminal.add(eventId)) {
        val outcome = if (action.kind == DshQuestionRegistry.ActionKind.DELEGATE) JSONObject().put("kind", "next")
          else rejected(action.code ?: "ASK_TIMED_OUT", "回答等待时间已结束")
        val job = scope.launch(start = CoroutineStart.LAZY) {
          try { settle(eventId, outcome) }
          catch (cancel: CancellationException) { throw cancel }
          catch (error: Exception) { onError(error) }
          finally {
            // Keep the business claim alive until the outcome has been sent. Releasing it first
            // lets the Host's own deadline win while ASK_TIMED_OUT/result is still in transit.
            synchronized(lock) { release(eventId) }
            if (!closed && action.callId != null) refreshSafely(action.sessionId)
          }
        }
        settlements += job
        job.invokeOnCompletion { synchronized(lock) { settlements.remove(job) } }
        job.start()
      }
    }
  }
  private suspend fun refreshSafely(sessionId: String) {
    try { refresh(sessionId) } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { onError(error) }
  }
  private fun claimVisible() {
    snapshots().filter { it.needsClaim }.forEach { snapshot ->
      val event = snapshot.eventId ?: return@forEach
      if (event in claims || event in terminal) return@forEach
      val claim = Claim()
      claims[event] = claim
      val job = scope.launch(start = CoroutineStart.LAZY) {
        var handle: Closeable? = null
        try {
          handle = openClaim(snapshot.sessionId, event, requireNotNull(snapshot.callId), { remaining ->
            synchronized(lock) {
              if (!closed && claims[event] === claim && !claim.opened) {
                claim.opened = true
                if (remaining < 0 || !registry.acceptClaim(snapshot.id, event, remaining, clock())) {
                  release(event); dispatch(registry.setPresentation(snapshot.id, false, false))
                }
                emit()
              }
            }
          }, {
            synchronized(lock) {
              if (claims[event] === claim) {
                release(event)
                dispatch(registry.setPresentation(snapshot.id, visible = false, foreground = false)); emit()
              }
            }
          })
          synchronized(lock) {
            if (!closed && claims[event] === claim) { claim.handle = handle; handle = null }
          }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { onError(error) }
        finally {
          handle?.let { runCatching { it.close() }.onFailure(onError) }
          synchronized(lock) {
            if (claims[event] === claim && claim.handle == null) {
              claims.remove(event)
              dispatch(registry.setPresentation(snapshot.id, false, false)); if (!closed) emit()
            }
          }
        }
      }
      claim.job = job
      job.start()
    }
  }
  private fun boundedSet() = object : LinkedHashSet<String>() {
    override fun add(element: String): Boolean {
      val added = super.add(element)
      if (size > 512) iterator().apply { next(); remove() }
      return added
    }
  }
  private fun rejected(code: String, message: String) = JSONObject().put("kind", "rejected")
    .put("error", JSONObject().put("name", "UserQuestionError").put("code", code).put("message", message))
}

/** Preserves DSH question IDs in field.key and uses option labels as their wire values. */
internal fun DshQuestionRegistry.Snapshot.toQuestionForm(directory: String): QuestionRequest {
  val fields = JSONArray()
  for (i in 0 until questions.length()) {
    val question = questions.getJSONObject(i)
    val options = JSONArray()
    val raw = question.optJSONArray("options") ?: JSONArray()
    for (j in 0 until raw.length()) {
      val option = raw.getJSONObject(j)
      options.put(JSONObject().put("label", option.getString("label"))
        .put("value", option.getString("label")).put("description", option.optString("description")))
    }
    fields.put(JSONObject().put("key", question.getString("id")).put("title", question.getString("question"))
      .put("description", question.optString("detail")).put("custom", true).put("options", options)
      .put("type", if (question.optBoolean("multiSelect")) "multiselect" else "select"))
  }
  return JSONObject().put("id", id).put("sessionID", sessionId).put("fields", fields).toForm(directory)
}
