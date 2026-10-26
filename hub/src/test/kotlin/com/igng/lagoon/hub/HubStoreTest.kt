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
