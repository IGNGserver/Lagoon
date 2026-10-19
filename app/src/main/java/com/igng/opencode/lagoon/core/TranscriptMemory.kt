package com.igng.opencode.lagoon.core

/**
 * The last few transcripts the user looked at, kept in memory. Going back to a conversation paints
 * it immediately instead of showing an empty page while the encrypted cache is decrypted or the
 * server is read; the server copy still replaces it afterwards.
 */
internal class TranscriptMemory(private val capacity: Int = 8, private val maxMessages: Int = 200) {
  private val entries = object : LinkedHashMap<String, List<Message>>(capacity, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Message>>?) = size > capacity
  }

  private fun key(serverId: String, sessionId: String) = "$serverId\u0000$sessionId"

  @Synchronized fun get(serverId: String, sessionId: String): List<Message>? = entries[key(serverId, sessionId)]

  @Synchronized fun put(serverId: String, sessionId: String, messages: List<Message>) {
    if (messages.isEmpty()) entries.remove(key(serverId, sessionId))
    else entries[key(serverId, sessionId)] = messages.takeLast(maxMessages)
  }

  @Synchronized fun forget(serverId: String, sessionId: String) { entries.remove(key(serverId, sessionId)) }

  @Synchronized fun forgetServer(serverId: String) { entries.keys.removeAll { it.startsWith("$serverId\u0000") } }
}
