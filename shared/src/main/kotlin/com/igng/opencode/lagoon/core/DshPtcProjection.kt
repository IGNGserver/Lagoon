package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The durable PTC (programmatic tool call) projection used by DSH's chat client.
 *
 * PTC records do not carry a turn/step location.  The root native tool call does, so a
 * caller must index roots before applying PTC records (history does this in a pre-pass).
 * This class is deliberately stateful: one instance belongs to one DSH session.  There is
 * no process-wide call registry, and an unindexed root never manufactures an assistant id.
 */
internal class DshPtcProjection(
  private val maxDepth: Int = MAX_DEPTH,
  private val maxEntries: Int = MAX_ENTRIES
) {
  init {
    require(maxDepth > 0) { "maxDepth must be positive" }
    require(maxEntries > 0) { "maxEntries must be positive" }
  }

  private var rootIndex = DshPtcRootIndex(emptyMap())
  private val nodes = LinkedHashMap<String, DshPtcToolState>()
  /** Cached edges can anchor a page before their outcomes replay, without suppressing its starts. */
  private data class Edge(val rootCallId: String, val parentCallId: String, val depth: Int)
  private val ancestry = LinkedHashMap<String, Edge>()
  private fun edge(id: String): Edge? = nodes[id]?.let { Edge(it.rootCallId, it.parentCallId, it.depth) } ?: ancestry[id]
  private fun forgetTree(rootCallId: String) {
    nodes.entries.removeAll { it.value.rootCallId == rootCallId }
    ancestry.entries.removeAll { it.value.rootCallId == rootCallId }
  }
  private fun makeRoom(rootCallId: String, subCallId: String): Boolean {
    if (subCallId in nodes || subCallId in ancestry) return true
    while ((nodes.keys + ancestry.keys).toSet().size >= maxEntries) {
      val victim = nodes.values.firstOrNull { it.rootCallId != rootCallId }?.rootCallId
        ?: ancestry.values.firstOrNull { it.rootCallId != rootCallId }?.rootCallId ?: return false
      forgetTree(victim)
      rootIndex = DshPtcRootIndex(rootIndex.roots.filterKeys { it != victim })
    }
    return true
  }

  /** Add root `tool/call` locations found in a complete history/snapshot pre-pass. */
  fun indexRoots(records: List<JSONObject>): DshPtcRootIndex {
    val merged = LinkedHashMap(rootIndex.roots)
    for (record in records) {
      val event = unpack(record) ?: continue
      if (event.type != ROOT_CALL_TYPE) continue
      val data = event.data
      val callId = nonBlank(data, "callId") ?: continue
      val assistantMessageId = explicitAssistantMessageId(data) ?: assistantMessageId(data) ?: continue
      if (merged.size >= maxEntries && callId !in merged) {
        val oldest = merged.keys.first()
        merged.remove(oldest)
        forgetTree(oldest)
      }
      // A durable call id is immutable.  Keeping the first location makes a repeated snapshot
      // and a live replay produce the same projection rather than whichever arrived last.
      if (callId !in merged) {
        merged[callId] = DshPtcRoot(
          callId = callId,
          assistantMessageId = assistantMessageId,
          name = nonBlank(data, "name").orEmpty(),
          arguments = copyJson(data.opt("arguments")),
          turn = positiveLong(data, "turn"),
          step = positiveLong(data, "step"),
          sequence = event.seq
        )
      }
    }
    rootIndex = DshPtcRootIndex(merged)
    return rootIndex
  }

  /** The current root index, useful when a live reducer receives one event at a time. */
  fun indexedRoots(): DshPtcRootIndex = rootIndex

  /** A page replays its own outcomes, retaining only roots/ancestors from other loaded windows. */
  fun replayFor(records: List<JSONObject>): DshPtcProjection {
    indexRoots(records)
    val replayedIds = records.mapNotNull { record ->
      unpack(record)?.data?.let { nonBlank(it, "subCallId") }
    }.toSet()
    return DshPtcProjection(maxDepth, maxEntries).also { replay ->
      replay.rootIndex = rootIndex
      replay.ancestry.putAll(ancestry)
      nodes.forEach { (id, node) ->
        if (id in replayedIds) replay.ancestry[id] = Edge(node.rootCallId, node.parentCallId, node.depth)
        else replay.nodes[id] = node.copyForRead()
      }
    }
  }

  /**
   * Process a durable event record.  Both the `/session/follow` record shape
   * (`{event:{type,data}}`) and its unwrapped `{type,data}` form are accepted.
   */
  fun process(record: JSONObject): DshPtcEvent? {
    val event = unpack(record) ?: return null
    return process(event.type, event.data, event.seq, event.time)
  }

  /** Process one PTC event after [indexRoots] has been called for its session. */
  fun process(type: String, data: JSONObject, seq: Long = 0L, time: Long = 0L): DshPtcEvent? {
    val kind = when (type) {
      START_TYPE, LEGACY_START_TYPE -> DshPtcEvent.Kind.START
      RESULT_TYPE, LEGACY_RESULT_TYPE -> DshPtcEvent.Kind.RESULT
      else -> return null
    }
    val rootCallId = nonBlank(data, "rootCallId") ?: return null
    val parentCallId = nonBlank(data, "parentCallId") ?: return null
    val subCallId = nonBlank(data, "subCallId") ?: return null
    val name = nonBlank(data, "name") ?: return null
    val root = rootIndex.roots[rootCallId] ?: return null
    if (parentCallId == subCallId || rootCallId == subCallId) return null

    val existing = nodes[subCallId]
    val depth = edgeDepth(rootCallId, parentCallId, subCallId, existing) ?: return null
    if (existing != null && kind == DshPtcEvent.Kind.START) return null

    val argumentsPresent = data.has("arguments")
    if (kind == DshPtcEvent.Kind.START && !argumentsPresent) return null
    if (kind == DshPtcEvent.Kind.RESULT && !argumentsPresent && existing == null) return null
    if (kind == DshPtcEvent.Kind.RESULT && !data.has("isError")) return null
    val arguments = if (argumentsPresent) copyJson(data.opt("arguments"))
      else copyJson(existing?.arguments)
    val content = if (kind == DshPtcEvent.Kind.RESULT) {
      // `content` is required by PtcDispatchEventData.  A missing array is malformed rather than
      // an empty successful result, which would hide a broken host payload.
      data.optJSONArray("content")?.let(::copyJsonArray) ?: return null
    } else JSONArray()
    if (!makeRoom(rootCallId, subCallId)) return null
    val isError = if (kind == DshPtcEvent.Kind.RESULT) data.optBoolean("isError") else false
    val error = if (kind == DshPtcEvent.Kind.RESULT) errorObject(data.opt("error"))?.apply {
      if (optString("message").isBlank()) put("message", optString("reason").ifBlank { content.contentText() }.ifBlank { "工具执行失败" })
    } else null
    val metadata = metadata(root, rootCallId, parentCallId, subCallId, name, data.opt("meta"))
    error?.let { metadata.put("error", JSONObject(it.toString())) }
    val next = DshPtcToolState(
      rootCallId = rootCallId,
      parentCallId = parentCallId,
      subCallId = subCallId,
      assistantMessageId = root.assistantMessageId,
      name = name,
      arguments = arguments,
      status = if (kind == DshPtcEvent.Kind.START) "running" else if (isError) "error" else "completed",
      content = content,
      isError = isError,
      meta = copyJson(data.opt("meta")),
      error = error,
      depth = depth,
      metadata = metadata
    )

    // A result can legally be observed before its start in a snapshot.  It is still accepted when
    // its parent is known, matching the official UI reducer.  A later start is a duplicate and is
    // intentionally ignored rather than replacing the settled result.
    ancestry.remove(subCallId)
    if (existing == null || kind == DshPtcEvent.Kind.RESULT) nodes[subCallId] = next
    return DshPtcEvent(kind = kind, state = next.copyForRead(), sequence = seq, time = time)
  }

  /** Alias used by reducers that call this operation a child projection. */
  fun projectChild(type: String, data: JSONObject, seq: Long = 0L, time: Long = 0L): DshPtcEvent? =
    process(type, data, seq, time)

  /** Return an immutable-in-practice snapshot of direct children in durable insertion order. */
  fun childrenOf(parentCallId: String): List<DshPtcToolState> =
    nodes.values.filter { it.parentCallId == parentCallId }.map { it.copyForRead() }

  /** Return a child by its official opaque sub-call id, without synthesizing an assistant row. */
  fun childProjection(subCallId: String): DshPtcToolState? = nodes[subCallId]?.copyForRead()

  private fun edgeDepth(rootCallId: String, parentCallId: String, subCallId: String,
    existing: DshPtcToolState?): Int? {
    val known = existing?.let { Edge(it.rootCallId, it.parentCallId, it.depth) } ?: ancestry[subCallId]
    if (known != null) {
      // A cached edge validates identity without treating its old outcome as a replayed start.
      if (known.rootCallId != rootCallId || known.parentCallId != parentCallId) return null
      return known.depth
    }

    if (parentCallId == rootCallId) return 1.takeIf { it <= maxDepth }
    val parent = edge(parentCallId) ?: return null
    if (parent.rootCallId != rootCallId) return null

    var cursor: String? = parentCallId
    var depth = parent.depth + 1
    val visited = HashSet<String>()
    while (cursor != null) {
      if (!visited.add(cursor) || cursor == subCallId) return null
      cursor = edge(cursor)?.parentCallId?.takeUnless { it == rootCallId }
    }
    return depth.takeIf { it <= maxDepth }
  }

  companion object {
    /** Matches the official dsh-client-ui-chat MAX_DEPTH guard. */
    const val MAX_DEPTH: Int = 256
    /** Bounds an accidentally unbounded hostile/malformed session without a global registry. */
    const val MAX_ENTRIES: Int = 4096

    /** Build an index without retaining any mutable projection state. */
    fun rootsFrom(records: List<JSONObject>, maxEntries: Int = MAX_ENTRIES): DshPtcRootIndex {
      return DshPtcProjection(maxEntries = maxEntries).indexRoots(records)
    }
  }
}

/** Official root call location used to anchor PTC children to one assistant message. */
internal data class DshPtcRoot(
  val callId: String,
  val assistantMessageId: String,
  val name: String,
  val arguments: Any?,
  val turn: Long?,
  val step: Long?,
  val sequence: Long
)

internal class DshPtcRootIndex internal constructor(
  roots: Map<String, DshPtcRoot>
) {
  val roots: Map<String, DshPtcRoot> = LinkedHashMap(roots)

  operator fun get(rootCallId: String): DshPtcRoot? = roots[rootCallId]
  fun assistantMessageId(rootCallId: String): String? = roots[rootCallId]?.assistantMessageId
}

/** Normalized child state.  [metadata] is ready to put in MessagePart.metadata. */
internal class DshPtcToolState(
  val rootCallId: String,
  val parentCallId: String,
  val subCallId: String,
  val assistantMessageId: String,
  val name: String,
  val arguments: Any?,
  val status: String,
  val content: JSONArray,
  val isError: Boolean,
  val meta: Any?,
  val error: JSONObject?,
  val depth: Int,
  val metadata: JSONObject
) {
  /** Properties for `session.tool.called` / `session.tool.success` reducers. */
  fun properties(): JSONObject = JSONObject()
    .put("assistantMessageID", assistantMessageId)
    .put("id", subCallId)
    .put("name", name)
    .put("input", copyJson(arguments) ?: JSONObject())
    .put("content", copyJsonArray(content))
    .put("metadata", JSONObject(metadata.toString()))
    .apply { error?.let { put("error", JSONObject(it.toString())) } }

  /** Same state in the existing assistant tool-part shape. */
  fun messagePart(): MessagePart {
    val state = JSONObject()
      .put("status", status)
      .put("input", copyJson(arguments) ?: JSONObject())
      .put("content", copyJsonArray(content))
      .put("metadata", JSONObject(metadata.toString()))
    error?.let { raw ->
      val normalized = JSONObject(raw.toString())
      if (!normalized.has("message")) normalized.optString("reason").takeIf { it.isNotBlank() }?.let {
        normalized.put("message", it)
      }
      state.put("error", normalized)
    }
    return JSONObject().put("type", "tool").put("id", subCallId).put("name", name).put("state", state)
      .toAssistantPart(subCallId)
  }

  internal fun copyForRead(): DshPtcToolState = DshPtcToolState(
    rootCallId, parentCallId, subCallId, assistantMessageId, name, copyJson(arguments), status,
    copyJsonArray(content), isError, copyJson(meta), error?.let { JSONObject(it.toString()) }, depth,
    JSONObject(metadata.toString())
  )
}

internal data class DshPtcEvent(
  val kind: Kind,
  val state: DshPtcToolState,
  val sequence: Long,
  val time: Long
) {
  enum class Kind { START, RESULT }

  val assistantMessageId: String get() = state.assistantMessageId
  val subCallId: String get() = state.subCallId
  val parentCallId: String get() = state.parentCallId
  val rootCallId: String get() = state.rootCallId
  val metadata: JSONObject get() = JSONObject(state.metadata.toString())
  fun properties(): JSONObject = state.properties()
}

/** Stateless convenience entry point for history pre-passes. */
internal fun indexRoots(records: List<JSONObject>): DshPtcRootIndex = DshPtcProjection.rootsFrom(records)

private const val ROOT_CALL_TYPE = "tool/call"
private const val START_TYPE = "tool/ptc-dispatch-start"
private const val RESULT_TYPE = "tool/ptc-dispatch"
private const val LEGACY_START_TYPE = "tool/code-dispatch-start"
private const val LEGACY_RESULT_TYPE = "tool/code-dispatch"

private data class RawPtcEvent(val type: String, val data: JSONObject, val seq: Long, val time: Long)

private fun unpack(record: JSONObject): RawPtcEvent? {
  val event = record.optJSONObject("event") ?: record
  val type = event.optString("type").takeIf { it.isNotBlank() } ?: return null
  val data = event.optJSONObject("data") ?: return null
  return RawPtcEvent(type, data, positiveLong(event, "seq") ?: 0L, positiveLong(event, "time") ?: 0L)
}

private fun nonBlank(data: JSONObject, key: String): String? =
  (data.opt(key) as? String)?.takeIf { it.isNotBlank() }

private fun positiveLong(data: JSONObject, key: String): Long? {
  val raw = data.opt(key) ?: return null
  val value = when (raw) {
    is Number -> raw.toLong()
    is String -> raw.trim().toLongOrNull()
    else -> null
  } ?: return null
  return value.takeIf { it > 0L }
}

private fun explicitAssistantMessageId(data: JSONObject): String? =
  listOf("assistantMessageID", "assistantMessageId", "messageID", "messageId")
    .firstNotNullOfOrNull { nonBlank(data, it) }

private fun assistantMessageId(data: JSONObject): String? {
  val turn = positiveLong(data, "turn") ?: return null
  val step = positiveLong(data, "step") ?: return null
  // Both coordinates are required.  In particular, never emit the historical msg_0:0 phantom.
  return "msg_$turn:$step"
}

private fun metadata(root: DshPtcRoot, rootCallId: String, parentCallId: String, subCallId: String,
  name: String, rawMeta: Any?): JSONObject =
  (rawMeta as? JSONObject)?.let { JSONObject(it.toString()) }?.apply {
    put("meta", JSONObject(rawMeta.toString()))
  }?.apply {
    put("tool", name)
    put("rootCallId", rootCallId)
    put("parentCallId", parentCallId)
    put("subCallId", subCallId)
    put("assistantMessageID", root.assistantMessageId)
  } ?: JSONObject()
    .put("tool", name)
    .put("rootCallId", rootCallId)
    .put("parentCallId", parentCallId)
    .put("subCallId", subCallId)
    .put("assistantMessageID", root.assistantMessageId)
    .apply { if (rawMeta != null && rawMeta != JSONObject.NULL) put("meta", copyJson(rawMeta)) }

private fun errorObject(raw: Any?): JSONObject? = when (raw) {
  null, JSONObject.NULL -> null
  is JSONObject -> JSONObject(raw.toString())
  is JSONArray -> JSONObject().put("details", JSONArray(raw.toString()))
  else -> JSONObject().put("message", raw.toString())
}

private fun copyJsonArray(array: JSONArray): JSONArray = JSONArray(array.toString())

private fun copyJson(value: Any?): Any? = when (value) {
  null, JSONObject.NULL -> null
  is JSONObject -> JSONObject(value.toString())
  is JSONArray -> JSONArray(value.toString())
  else -> value
}
