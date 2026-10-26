package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.Project
import com.igng.opencode.lagoon.core.ServerCredentials
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.SessionNotice
import com.igng.opencode.lagoon.core.TaskPhase
import com.igng.opencode.lagoon.core.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class HubStoreTest {
  private val dir = Files.createTempDirectory("hub-store")
  private val box = SecretBox.load(dir)
  private val profile = ServerProfile("a", "Dev", "http://10.0.0.2:4096", "opencode", allowCleartext = true)

  @Test fun everythingSurvivesARestart() {
    HubStore(dir, box).use { store ->
      store.saveServer(profile, ServerCredentials("opencode", "pw", "c=1"))
      store.addProject("a", Project("p", "/repo", "repo"))
      store.rememberTask("a", TaskState("s", TaskPhase.THINKING, "运行中", since = 10, activeAt = 5), parent = "root")
      store.rememberNotice("a", SessionNotice("n", "s", System.currentTimeMillis(), error = false))
      store.rememberCatalog("a", listOf(Project("p", "/repo", "repo")), listOf(Session("s", "/repo", "Task", 3)))
    }
    HubStore(dir, box).use { store ->
      assertEquals(listOf(profile), store.profiles())
      assertEquals(ServerCredentials("opencode", "pw", "c=1"), store.credentials("a"))
      assertEquals("/repo", store.localProjects("a").single().directory)
      assertEquals(TaskPhase.THINKING, store.taskStates("a")["s"]?.phase)
      assertEquals(5L, store.taskStates("a")["s"]?.activeAt)
      assertEquals("root", store.taskParents("a")["s"])
      assertEquals("n", store.sessionNotices("a").single().id)
      assertEquals("Task", store.catalog("a")!!.second.single().title)
    }
    // Secrets never reach the state file in clear text.
    assertFalse(Files.readString(dir.resolve("state.json")).contains("pw"))
  }

  @Test fun credentialsDoNotFollowAProfileToAnotherAddress() {
    HubStore(dir, box).use { store ->
      store.saveServer(profile, ServerCredentials("opencode", "pw"))
      store.saveServer(profile.copy(url = "http://10.0.0.3:4096"), store.credentials("a").copy(password = ""))
      assertEquals("", store.credentials("a").password)
    }
  }

  @Test fun reconciliationFlagsSurviveCodecAndStoreRoundTrips() {
    val now = System.currentTimeMillis()
    val states = listOf(false, true).flatMap { uncertain ->
      listOf(false, true).map { pendingInput ->
        TaskState("s-$uncertain-$pendingInput", TaskPhase.WAITING_SERVER, "等待服务器处理", since = now,
          uncertain = uncertain, pendingInput = pendingInput)
      }
    }
    HubStore(dir, box).use { store ->
      states.forEach { state ->
        val json = Codec.task(state)
        assertEquals(state.uncertain, json.getBoolean("uncertain"))
        assertEquals(state.pendingInput, json.getBoolean("pendingInput"))
        assertEquals(state, Decode.task(state.sessionId, json))
        store.rememberTask("a", state, observedAt = now)
      }
    }
    HubStore(dir, box).use { store ->
      assertEquals(states.associateBy { it.sessionId }, store.taskStates("a"))
    }
  }

  @Test fun olderTaskJsonDefaultsToVerifiedWithNoPendingInput() {
    val state = TaskState("s", TaskPhase.THINKING, since = 100)
    val json = Codec.task(state).apply { remove("uncertain"); remove("pendingInput") }
    assertEquals(state, Decode.task("s", json))
  }

  @Test fun rememberedCookiesAreBoundToTheAddressTheyWereMintedFor() {
    HubStore(dir, box).use { store ->
      val dsh = profile.copy(kind = ServerKind.DSH, username = "dsh")
      store.saveServer(dsh, ServerCredentials("dsh", "token"))
      store.rememberCookie("a", "http://elsewhere", "dsh-auth=x")
      assertEquals("", store.credentials("a").cookie)
      store.rememberCookie("a", dsh.url, "dsh-auth=x")
      assertEquals("dsh-auth=x", store.credentials("a").cookie)
      assertEquals("token", store.credentials("a").password)
    }
  }

  @Test fun anOlderObservationNeverOverwritesANewerTask() {
    HubStore(dir, box).use { store ->
      store.rememberTask("a", TaskState("s", TaskPhase.COMPLETED, "任务已完成", since = 1), observedAt = 200)
      store.rememberTask("a", TaskState("s", TaskPhase.THINKING, "运行中", since = 1), observedAt = 100)
      assertEquals(TaskPhase.COMPLETED, store.taskStates("a")["s"]?.phase)
    }
  }

  @Test fun aNewRunOfAnIdleSessionStartsWhenObserved() {
    HubStore(dir, box).use { store ->
      store.rememberTask("a", TaskState("s", TaskPhase.COMPLETED, "任务已完成", since = 50), observedAt = 100)
      val next = store.rememberTask("a", TaskState("s", TaskPhase.THINKING, "运行中", since = 10), observedAt = 300)
      assertEquals(300, next.since)
    }
  }

  @Test fun readingAndForgettingResults() {
    HubStore(dir, box).use { store ->
      val now = System.currentTimeMillis()
      store.rememberNotice("a", SessionNotice("n1", "s1", now, false))
      store.rememberNotice("a", SessionNotice("n2", "s2", now, true))
      store.rememberNotice("a", SessionNotice("n1", "s1", now, false))
      assertEquals(2, store.sessionNotices("a").size)
      store.viewNotices("a", "s1", setOf("n1"))
      assertTrue(store.sessionNotices("a").first { it.id == "n1" }.viewed)
      assertFalse(store.sessionNotices("a").first { it.id == "n2" }.viewed)
      store.viewAllNotices("a")
      assertTrue(store.sessionNotices("a").all { it.viewed })
      store.forgetSession("a", "s2")
      assertEquals(listOf("n1"), store.sessionNotices("a").map { it.id })
    }
  }

  @Test fun forgettingATaskRetiresOnlyItsServerScopedLedgersAndPersists() {
    val now = System.currentTimeMillis()
    HubStore(dir, box).use { store ->
      store.addProject("a", Project("p", "/repo", "repo"))
      store.rememberCatalog("a", emptyList(), listOf(Session("s", "/repo", "Task", now)))
      store.rememberTask("a", TaskState("s", TaskPhase.THINKING), parent = "root")
      store.rememberTask("a", TaskState("other", TaskPhase.THINKING), parent = "root")
      store.rememberTask("b", TaskState("s", TaskPhase.THINKING), parent = "other-root")
      store.rememberNotice("a", SessionNotice("n", "s", now, false))
      store.rememberNotice("a", SessionNotice("other-n", "other", now, false))
      store.rememberNotice("b", SessionNotice("n", "s", now, false))
      store.forgetTask("a", "s")
      assertFalse(store.taskStates("a").containsKey("s"))
      assertFalse(store.taskParents("a").containsKey("s"))
      assertEquals(listOf("other-n"), store.sessionNotices("a").map { it.id })
    }
    HubStore(dir, box).use { store ->
      assertEquals(setOf("other"), store.taskStates("a").keys)
      assertEquals(mapOf("other" to "root"), store.taskParents("a"))
      assertEquals(listOf("other-n"), store.sessionNotices("a").map { it.id })
      assertEquals(setOf("s"), store.taskStates("b").keys)
      assertEquals(mapOf("s" to "other-root"), store.taskParents("b"))
      assertEquals(listOf("n"), store.sessionNotices("b").map { it.id })
      assertEquals("/repo", store.localProjects("a").single().directory)
      assertEquals("s", store.catalog("a")!!.second.single().id)
      store.forgetSession("a", "other")
      assertTrue(store.taskStates("a").isEmpty())
      assertTrue(store.taskParents("a").isEmpty())
      assertTrue(store.sessionNotices("a").isEmpty())
    }
  }

  @Test fun aCorruptStateFileIsSetAsideNotSilentlyMerged() {
    Files.writeString(dir.resolve("state.json"), "{not json")
    HubStore(dir, box).use { store -> assertTrue(store.profiles().isEmpty()) }
    assertTrue(Files.list(dir).anyMatch { it.fileName.toString().startsWith("state.json.broken-") })
  }

  @Test fun deletingAServerDropsItsLedger() {
    HubStore(dir, box).use { store ->
      store.saveServer(profile, ServerCredentials("opencode", "pw"))
      store.rememberTask("a", TaskState("s", TaskPhase.THINKING))
      store.deleteServer("a")
      assertTrue(store.profiles().isEmpty())
      assertTrue(store.taskStates("a").isEmpty())
      assertFalse(store.hasSecret("a"))
    }
  }
}
