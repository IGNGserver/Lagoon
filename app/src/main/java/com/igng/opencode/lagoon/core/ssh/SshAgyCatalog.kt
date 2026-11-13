package com.igng.opencode.lagoon.core.ssh

import java.util.Base64
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Read only the workspace URIs belonging to the already returned ACP session IDs. */
internal object SshAgyCatalog {
  private const val PREFIX = "LAGOON_AGY_WORKSPACES "
  fun enrich(ssh: SshTransport, response: JSONObject): JSONObject {
    val sessions = response.optJSONArray("sessions") ?: return response
    if (sessions.length() == 0) return response.put("_lagoonWorkspaceLookupComplete", true)
    if (sessions.length() > 2_000) return response.put("_lagoonWorkspaceLookupComplete", false)
    val ids = JSONArray()
    for (i in 0 until sessions.length()) {
      val id = sessions.optJSONObject(i)?.opt("sessionId") as? String
      if (id.isNullOrBlank() || id.length > 256 || id.any(Char::isISOControl))
        return response.put("_lagoonWorkspaceLookupComplete", false)
      ids.put(id)
    }
    val encoded = Base64.getEncoder().encodeToString(ids.toString().toByteArray(Charsets.UTF_8))
    val result = try { ssh.exec(script(encoded), 15_000) }
      catch (cancel: CancellationException) { throw cancel }
      catch (_: Exception) { null }
    if (result?.exitCode != 0) return response.put("_lagoonWorkspaceLookupComplete", false)
    val lines = result.stdout.lineSequence().filter { it.startsWith(PREFIX) }.toList()
    if (lines.size != 1) return response.put("_lagoonWorkspaceLookupComplete", false)
    val paths = try { JSONObject(lines.single().removePrefix(PREFIX)) } catch (_: Exception) {
      return response.put("_lagoonWorkspaceLookupComplete", false)
    }
    var complete = true
    for (i in 0 until sessions.length()) {
      val item = sessions.getJSONObject(i)
      val id = item.getString("sessionId")
      val values = paths.optJSONArray(id)
      if (values == null || values.length() > 64) { complete = false; continue }
      val clean = JSONArray()
      for (index in 0 until values.length()) {
        val path = values.opt(index) as? String
        if (path.isNullOrBlank() || !path.startsWith('/') || path.length > 4096 || path.any(Char::isISOControl)) {
          complete = false; continue
        }
        clean.put(path)
      }
      item.put("_lagoonWorkspaces", clean)
    }
    return response.put("_lagoonWorkspaceLookupComplete", complete)
  }

  // Only base64-encoded session IDs are interpolated. The database is opened read-only,
  // SQL uses bound parameters, and the client never supplies a file path or command.
  private fun script(ids: String) = """
    python3 - '$ids' <<'LAGOON_AGY_PY'
import base64, json, os, sqlite3, sys, urllib.parse, urllib.request
try:
    ids = json.loads(base64.b64decode(sys.argv[1], validate=True).decode('utf-8'))
    if not isinstance(ids, list) or len(ids) > 2000 or any(not isinstance(i, str) or len(i) > 256 for i in ids): raise ValueError()
    db = os.path.expanduser('~/.gemini/antigravity-cli/conversation_summaries.db')
    con = sqlite3.connect('file:' + urllib.parse.quote(db) + '?mode=ro', uri=True, timeout=5)
    output = {}
    try:
        for start in range(0, len(ids), 400):
            chunk = ids[start:start + 400]
            query = 'SELECT conversation_id, workspace_uris FROM conversation_summaries WHERE conversation_id IN (' + ','.join('?' for _ in chunk) + ')'
            for ident, raw in con.execute(query, chunk):
                uris = json.loads(raw or '[]')
                if not isinstance(uris, list) or len(uris) > 64: raise ValueError()
                paths = []
                for uri in uris:
                    if not isinstance(uri, str): continue
                    parsed = urllib.parse.urlparse(uri)
                    if parsed.scheme != 'file' or parsed.netloc not in ('', 'localhost'): continue
                    path = urllib.request.url2pathname(parsed.path)
                    if not os.path.isabs(path) or len(path) > 4096 or any(ord(c) < 32 or ord(c) == 127 for c in path): continue
                    path = os.path.realpath(path)
                    if path not in paths: paths.append(path)
                output[ident] = paths
    finally:
        con.close()
    encoded = json.dumps(output, ensure_ascii=True, separators=(',', ':'))
    if len(encoded.encode('utf-8')) > 450000: raise ValueError()
    print('LAGOON_AGY_WORKSPACES ' + encoded)
except Exception:
    sys.exit(1)
LAGOON_AGY_PY
  """.trimIndent()
}
