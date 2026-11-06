package com.igng.opencode.lagoon.core

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingCachesTest {
  private fun view(text: String = "hello", updated: Long = 10, at: Long = 1_000) = CachedSessionView(
    messages = listOf(Message("m", "user", 1, listOf(MessagePart("p", "text", text)))),
    olderCursor = "c",
    loadedOlder = false,
    sessionUpdated = updated,
    cacheComplete = true,
    at = at
  )

  @Test fun freshViewMatchesTheSameServerRevisionAndExpires() {
    val cache = SessionViewCache(ttlMillis = 100)
    val session = Session("s", "/repo", "S", updated = 10)
    cache.put("server", "s", view(updated = 10, at = 1_000))
    assertTrue(cache.fresh(cache.get("server", "s"), session, now = 1_050))
    assertFalse(cache.fresh(cache.get("server", "s"), session, now = 1_200))
    assertFalse(cache.fresh(cache.get("server", "s"), session.copy(updated = 11), now = 1_050))
  }

  @Test fun choicesCacheIsServerAndDirectoryScoped() {
    val cache = ChoicesCache(ttlMillis = 100)
    val value = CachedChoices("/repo/", emptyList(), emptyList(), emptyList(), at = 1_000, complete = true)
    cache.put("a", value)
    assertNotNull(cache.get("a", "/repo", now = 1_050))
    assertNull(cache.get("b", "/repo", now = 1_050))
    assertNotNull(cache.get("a", "/repo/", now = 1_050))
    assertNull(cache.get("a", "/repo", now = 1_200))
    cache.invalidateServer("a")
    assertNull(cache.get("a", "/repo", now = 1_050))
  }

  @Test fun keyedJobsCancelThePreviousReadForTheSameKey() {
    val jobs = KeyedJobs()
    val first = Job()
    val second = Job()
    jobs.replace("session:a", first)
    jobs.replace("session:a", second)
    assertTrue(first.isCancelled)
    assertFalse(second.isCancelled)
    jobs.cancelWhere { it == "session:a" }
    assertTrue(second.isCancelled)
  }
}
