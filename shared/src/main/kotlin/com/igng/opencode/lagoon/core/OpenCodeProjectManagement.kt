package com.igng.opencode.lagoon.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Native OpenCode project metadata; never a Lagoon/DSH workspace registration. */
data class OpenCodeProjectInfo(
  val id: String, val canonical: String, val name: String?, val vcs: String?,
  val iconUrl: String?, val iconOverride: String?, val iconColor: String?, val startCommand: String?,
  val created: Long, val updated: Long, val active: Long, val archived: Long?, val sandboxes: List<String>
) {
  override fun toString(): String = "OpenCodeProjectInfo(id=$id, canonical=$canonical, metadata=<redacted>)"
}

/** Null keeps the native field; an empty optional string explicitly clears it. */
data class OpenCodeProjectEdit(
  val canonical: String? = null, val name: String? = null, val iconOverride: String? = null,
  val iconColor: String? = null, val startCommand: String? = null
) {
  override fun toString(): String = "OpenCodeProjectEdit(<redacted>)"
}
data class OpenCodeWorktree(val directory: String, val strategy: String?)
/** [parentDirectory] is the parent, not the eventual server-selected worktree path. */
data class OpenCodeWorktreeCreate(
  val from: String? = null, val branch: String? = null, val parentDirectory: String? = null, val name: String? = null
)

/** Fixed native 2.2.6 routes. The carrier is single-send; no endpoint probing or mutation replay. */
internal class OpenCodeProjectManagement(
  private val capabilities: () -> ApiCapabilities,
  private val request: suspend (String, String, Map<String, String>, JSONObject?) -> String,
  private val invalidateDisplay: () -> Unit
) {
  private fun projectEnabled() = capabilities().let { it.openCodeProjectManagement || it.openCodeWorktreeManagement }
  suspend fun projects(): List<OpenCodeProjectInfo>? {
    if (!projectEnabled()) return null
    return freshProjects()
  }
  private suspend fun freshProjects(): List<OpenCodeProjectInfo> = withContext(Dispatchers.IO) {
    val items = array(request("GET", "api/project", mapOf("archived" to "true"), null))
    val result = (0 until items.length()).map { project(items.opt(it) as? JSONObject ?: invalid()) }
    if (result.map { it.id }.toSet().size != result.size) invalid()
    result
  }
  private suspend fun preflight(expected: OpenCodeProjectInfo): OpenCodeProjectInfo {
    if (!validId(expected.id) || !validPath(expected.canonical)) badInput()
    val current = freshProjects().singleOrNull { it.id == expected.id }
      ?: throw ApiException(409, "项目已不在服务器清单中，请刷新后重试")
    // Activity recency is not a metadata revision. The native API has no atomic CAS.
    if (current.canonical != expected.canonical || current.name != expected.name ||
      current.iconOverride != expected.iconOverride || current.iconColor != expected.iconColor ||
      current.startCommand != expected.startCommand || current.updated != expected.updated)
      throw ApiException(409, "项目资料已变化，请刷新后重新确认")
    return current
  }
  suspend fun update(expected: OpenCodeProjectInfo, edit: OpenCodeProjectEdit): OpenCodeProjectInfo? {
    if (!capabilities().openCodeProjectManagement) return null
    if (!validPathId(expected.id)) badInput()
    edit.canonical?.let { if (!validPath(it)) badInput() }
    listOf(edit.name, edit.iconOverride, edit.iconColor, edit.startCommand).forEach { if (it != null && !validText(it)) badInput() }
    preflight(expected)
    val body = JSONObject()
    if (edit.canonical != null && edit.canonical != expected.canonical) body.put("canonical", edit.canonical)
    if (edit.name != null && edit.name != expected.name) body.put("name", edit.name)
    val icon = JSONObject()
    if (edit.iconOverride != null && edit.iconOverride != expected.iconOverride) icon.put("override", edit.iconOverride)
    if (edit.iconColor != null && edit.iconColor != expected.iconColor) icon.put("color", edit.iconColor)
    if (icon.length() != 0) body.put("icon", icon)
    if (edit.startCommand != null && edit.startCommand != expected.startCommand)
      body.put("commands", JSONObject().put("start", edit.startCommand))
    if (body.length() == 0) return expected
    return try {
      val raw = request("PATCH", "api/project/${segment(expected.id)}", emptyMap(), body)
      withContext(Dispatchers.IO) { project(objectValue(raw)) }.also { if (it.id != expected.id) invalid() }
    } finally { invalidateDisplay() }
  }
  suspend fun worktrees(expected: OpenCodeProjectInfo): List<OpenCodeWorktree>? {
    if (!capabilities().openCodeWorktreeManagement) return null
    preflight(expected)
    return inventory(expected.id)
  }
  private suspend fun inventory(id: String): List<OpenCodeWorktree> = withContext(Dispatchers.IO) {
    val items = array(request("GET", "api/worktree", mapOf("projectID" to id), null))
    val result = (0 until items.length()).map { worktree(items.opt(it) as? JSONObject ?: invalid()) }
    if (result.map { it.directory }.toSet().size != result.size) invalid()
    result
  }
  suspend fun create(expected: OpenCodeProjectInfo, input: OpenCodeWorktreeCreate): OpenCodeWorktree? {
    if (!capabilities().openCodeWorktreeManagement) return null
    input.from?.let { if (!validPath(it)) badInput() }
    input.parentDirectory?.let { if (!validPath(it)) badInput() }
    input.branch?.let { if (it.isBlank() || it != it.trim() || it.length > 256 || it.any(Char::isISOControl)) badInput() }
    input.name?.let {
      if (it.isBlank() || it.length > 256 || it in setOf(".", "..") || it.any { c -> c == '/' || c == '\\' || c.isISOControl() }) badInput()
    }
    preflight(expected)
    if (input.from != null && inventory(expected.id).none { it.directory == input.from })
      throw ApiException(409, "来源工作树已不在服务器清单中，请刷新后重试")
    val body = JSONObject().put("projectID", expected.id)
    input.from?.let { body.put("from", it) }; input.branch?.let { body.put("branch", it) }
    input.parentDirectory?.let { body.put("directory", it) }; input.name?.let { body.put("name", it) }
    return try {
      val raw = request("POST", "api/worktree", emptyMap(), body)
      withContext(Dispatchers.IO) { worktree(objectValue(raw)) }
    } finally { invalidateDisplay() }
  }
  suspend fun remove(expected: OpenCodeProjectInfo, target: OpenCodeWorktree, force: Boolean): Boolean? {
    if (!capabilities().openCodeWorktreeManagement) return null
    if (!validPath(target.directory) || target.strategy.isNullOrBlank() || !validText(target.strategy)) badInput()
    preflight(expected)
    val current = inventory(expected.id).singleOrNull { it.directory == target.directory }
    if (current == null || current != target || current.strategy.isNullOrBlank() || current.directory == expected.canonical)
      throw ApiException(409, "工作树归属已变化或不是可移除工作树，请刷新后重试")
    return try {
      request("DELETE", "api/worktree", emptyMap(), JSONObject().put("projectID", expected.id)
        .put("directory", target.directory).put("force", force))
      true
    } finally { invalidateDisplay() }
  }
  suspend fun refresh(expected: OpenCodeProjectInfo): Boolean? {
    if (!capabilities().openCodeWorktreeManagement) return null
    preflight(expected)
    return try {
      request("POST", "api/worktree/refresh", emptyMap(), JSONObject().put("projectID", expected.id))
      true
    } finally { invalidateDisplay() }
  }
  private fun project(value: JSONObject): OpenCodeProjectInfo {
    val id = requiredString(value, "id").also { if (!validId(it)) invalid() }
    val canonical = requiredString(value, "canonical").also { if (!validPath(it)) invalid() }
    val icon = optionalObject(value, "icon"); val commands = optionalObject(value, "commands")
    val time = value.opt("time") as? JSONObject ?: invalid()
    val sandboxes = value.opt("sandboxes") as? JSONArray ?: invalid()
    if (sandboxes.length() > MAX_ITEMS) invalid()
    val paths = (0 until sandboxes.length()).map { (sandboxes.opt(it) as? String ?: invalid()).also { p -> if (!validPath(p)) invalid() } }
    if (paths.toSet().size != paths.size) invalid()
    return OpenCodeProjectInfo(id, canonical, optionalString(value, "name"), optionalString(value, "vcs"),
      icon?.let { optionalString(it, "url") }, icon?.let { optionalString(it, "override") },
      icon?.let { optionalString(it, "color") }, commands?.let { optionalString(it, "start") },
      integer(time, "created"), integer(time, "updated"), integer(time, "active"),
      if (!time.has("archived") || time.isNull("archived")) null else integer(time, "archived"), paths)
  }
  private fun worktree(value: JSONObject): OpenCodeWorktree = OpenCodeWorktree(
    requiredString(value, "directory").also { if (!validPath(it)) invalid() }, optionalString(value, "strategy"))
  private fun integer(value: JSONObject, key: String): Long {
    val raw = value.opt(key) as? Number ?: invalid()
    return try { BigDecimal(raw.toString()).longValueExact().also { if (it !in 0..9_007_199_254_740_991L) invalid() } }
      catch (_: NumberFormatException) { invalid() } catch (_: ArithmeticException) { invalid() }
  }
  private fun requiredString(value: JSONObject, key: String): String = (value.opt(key) as? String ?: invalid()).also { if (!validText(it)) invalid() }
  private fun optionalString(value: JSONObject, key: String): String? =
    if (!value.has(key) || value.isNull(key)) null else requiredString(value, key)
  private fun optionalObject(value: JSONObject, key: String): JSONObject? =
    if (!value.has(key) || value.isNull(key)) null else value.opt(key) as? JSONObject ?: invalid()
  private fun parsed(raw: String): Any {
    try {
      StrictProjectJson(raw).validate()
      return if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw)
    } catch (_: Exception) { invalid() }
  }
  private fun array(raw: String): JSONArray {
    val parsed = parsed(raw)
    val value = parsed as? JSONArray ?: (parsed as? JSONObject)?.opt("data") as? JSONArray ?: invalid()
    if (value.length() > MAX_ITEMS) invalid()
    return value
  }
  private fun objectValue(raw: String): JSONObject {
    val value = parsed(raw) as? JSONObject ?: invalid()
    return if (value.has("data")) value.opt("data") as? JSONObject ?: invalid() else value
  }
  companion object {
    private const val MAX_ITEMS = 5000
    private fun validText(value: String) = value.length <= 16_384 && '\u0000' !in value
    private fun validId(value: String) = value.isNotBlank() && value.length <= 512 && value.none(Char::isISOControl)
    /** The PATCH route puts the ID in one path segment; the transport forbids encoded separators. */
    private fun validPathId(value: String) = validId(value) && value != "." && value != ".." &&
      value.none { it == '/' || it == '\\' || it == '%' }
    private fun validPath(value: String) = value.isNotBlank() && value.length <= 4096 && value.none(Char::isISOControl) &&
      (value.startsWith('/') || Regex("^[A-Za-z]:[\\\\/].*").matches(value) ||
        Regex("^\\\\\\\\[^\\\\/]+[\\\\/][^\\\\/]+(?:[\\\\/].*)?$").matches(value))
    private fun segment(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun invalid(): Nothing = throw ApiException(502, "OpenCode 项目或工作树响应不符合原生契约，未使用不完整结果")
    private fun badInput(): Nothing = throw ApiException(400, "项目或工作树参数无效")
  }
}

/** org.json is intentionally lenient; validate JSON grammar before decoding native metadata. */
private class StrictProjectJson(private val text: String) {
  private var at = 0
  fun validate() { value(0); whitespace(); require(at == text.length) }
  private fun whitespace() { while (at < text.length && text[at] in " \t\r\n") at++ }
  private fun take(c: Char): Boolean { whitespace(); return if (at < text.length && text[at] == c) { at++; true } else false }
  private fun value(depth: Int) {
    require(depth < 64); whitespace(); require(at < text.length)
    when (text[at]) {
      '{' -> { at++; if (!take('}')) { val keys = mutableSetOf<String>(); do {
        whitespace(); val start = at; string(); val key = JSONArray("[${text.substring(start, at)}]").getString(0)
        require(keys.add(key)); require(take(':')); value(depth + 1)
      } while (take(',')); require(take('}')) } }
      '[' -> { at++; if (!take(']')) { do { value(depth + 1) } while (take(',')); require(take(']')) } }
      '"' -> string()
      't' -> literal("true")
      'f' -> literal("false")
      'n' -> literal("null")
      else -> { val start = at
        if (text[at] == '-') at++
        require(at < text.length)
        if (text[at] == '0') at++ else { require(text[at] in '1'..'9'); while (at < text.length && text[at] in '0'..'9') at++ }
        if (at < text.length && text[at] == '.') { at++; val digits = at; while (at < text.length && text[at] in '0'..'9') at++; require(at > digits) }
        if (at < text.length && text[at] in "eE") { at++; if (at < text.length && text[at] in "+-") at++; val digits = at
          while (at < text.length && text[at] in '0'..'9') at++; require(at > digits) }
        require(at > start)
      }
    }
  }
  private fun literal(value: String) { require(text.startsWith(value, at)); at += value.length }
  private fun string() {
    require(at < text.length && text[at++] == '"')
    while (at < text.length) {
      val c = text[at++]; if (c == '"') return
      require(c >= ' ')
      if (c == '\\') {
        require(at < text.length); val escaped = text[at++]
        require(escaped in "\"\\/bfnrtu")
        if (escaped == 'u') { repeat(4) { require(at < text.length && text[at++] in "0123456789abcdefABCDEF") } }
      }
    }
    error("Unterminated JSON string")
  }
}

/** Only this instance's documented native write shapes may enable the management UI. */
internal fun openCodeProjectCapabilities(document: JSONObject): Pair<Boolean, Boolean> {
  val paths = document.optJSONObject("paths") ?: return false to false
  fun schema(value: JSONObject, depth: Int = 0): JSONObject? {
    if (depth >= 20) return null
    if (value.has("${'$'}ref")) {
      val ref = value.opt("${'$'}ref") as? String ?: return null
      if (!ref.startsWith("#/")) return null
      var node: Any = document
      for (key in ref.removePrefix("#/").split('/')) node = (node as? JSONObject)?.opt(key.replace("~1", "/").replace("~0", "~")) ?: return null
      return schema(node as? JSONObject ?: return null, depth + 1)
    }
    for (union in listOf("anyOf", "oneOf")) if (value.has(union)) {
      val branches = value.optJSONArray(union) ?: return null
      val resolved = (0 until branches.length()).map { schema(branches.optJSONObject(it) ?: return null, depth + 1) ?: return null }
        .filter { it.opt("type") != "null" }
      return if (resolved.size == 1) resolved.single() else null
    }
    return value
  }
  fun type(raw: JSONObject?, expected: String): JSONObject? {
    val resolved = schema(raw ?: return null) ?: return null
    val kind = resolved.opt("type")
    val valid = kind == expected || kind is JSONArray && (0 until kind.length()).map { kind.opt(it) }.toSet() == setOf(expected, "null")
    return resolved.takeIf { valid }
  }
  fun required(value: JSONObject): Set<String>? {
    if (!value.has("required")) return emptySet()
    val raw = value.optJSONArray("required") ?: return null
    return (0 until raw.length()).map { raw.opt(it) as? String ?: return null }.toSet()
  }
  fun shape(raw: JSONObject?, fields: Map<String, String>, mandatory: Set<String> = emptySet()): JSONObject? {
    val value = type(raw, "object") ?: return null
    if (required(value) != mandatory) return null
    val props = value.optJSONObject("properties") ?: return null
    if (fields.any { (key, kind) -> type(props.optJSONObject(key), kind) == null }) return null
    return value
  }
  fun body(op: JSONObject?): JSONObject? {
    val request = schema(op?.optJSONObject("requestBody") ?: return null) ?: return null
    return request.optJSONObject("content")?.optJSONObject("application/json")?.optJSONObject("schema")
  }
  val projectGet = paths.optJSONObject("/api/project")?.optJSONObject("get") != null
  val projectPatch = paths.keys().asSequence().filter { Regex("^/api/project/\\{[^/}]+\\}$").matches(it) }.any { path ->
    val input = shape(body(paths.optJSONObject(path)?.optJSONObject("patch")), mapOf("canonical" to "string", "name" to "string", "icon" to "object", "commands" to "object")) ?: return@any false
    val props = input.optJSONObject("properties") ?: return@any false
    shape(props.optJSONObject("icon"), mapOf("override" to "string", "color" to "string")) != null &&
      shape(props.optJSONObject("commands"), mapOf("start" to "string")) != null
  }
  val worktree = paths.optJSONObject("/api/worktree")
  val get = worktree?.optJSONObject("get")
  val parameters = listOfNotNull(worktree, get).flatMap { item -> item.optJSONArray("parameters")?.let { a ->
    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { p -> schema(p) } }
  }.orEmpty() }
  val queryCompatible = get != null && parameters.any { p -> p.opt("in") == "query" && p.opt("name") == "projectID" && p.opt("required") == true && type(p.optJSONObject("schema"), "string") != null } &&
    parameters.none { p -> p.opt("required") == true && !(p.opt("in") == "query" && p.opt("name") == "projectID") }
  val create = shape(body(worktree?.optJSONObject("post")), mapOf("projectID" to "string", "from" to "string", "branch" to "string", "directory" to "string", "name" to "string"), setOf("projectID")) != null
  val remove = shape(body(worktree?.optJSONObject("delete")), mapOf("projectID" to "string", "directory" to "string", "force" to "boolean"), setOf("projectID", "directory", "force")) != null
  val refresh = shape(body(paths.optJSONObject("/api/worktree/refresh")?.optJSONObject("post")), mapOf("projectID" to "string"), setOf("projectID")) != null
  return (projectGet && projectPatch) to (projectGet && queryCompatible && create && remove && refresh)
}
