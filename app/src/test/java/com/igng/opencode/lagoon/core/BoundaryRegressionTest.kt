package com.igng.opencode.lagoon.core

import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun memoryPreferences(): SharedPreferences {
  val values=java.util.Collections.synchronizedMap(mutableMapOf<String,Any?>())
  val loader=SharedPreferences::class.java.classLoader
  return Proxy.newProxyInstance(loader,arrayOf(SharedPreferences::class.java)) { _, method,args ->
    when(method.name) {
      "getAll" -> synchronized(values){values.toMap()}
      "contains" -> values.containsKey(args!![0])
      "edit" -> {
        val changes=mutableMapOf<String,Any?>()
        Proxy.newProxyInstance(loader,arrayOf(SharedPreferences.Editor::class.java)) { proxy,m,a ->
          when {
            m.name.startsWith("put") -> { changes[a!![0] as String]=a[1];proxy }
            m.name=="remove" -> {changes[a!![0] as String]=null;proxy}
            m.name=="commit" || m.name=="apply" -> {synchronized(values){changes.forEach{(k,v)->if(v==null)values.remove(k) else values[k]=v}};if(m.name=="commit")true else null}
            else -> null
          }
        }
      }
      "getString","getLong","getInt","getBoolean","getFloat","getStringSet" -> values[args!![0]]?:args[1]
      else -> null
    }
  } as SharedPreferences
}
class BoundaryRegressionTest {
  private fun store(p:SharedPreferences=memoryPreferences(),secret:SharedPreferences=memoryPreferences())=ServerStore(p,secret,{"encrypted:$it"},{require(it.startsWith("encrypted:"));it.removePrefix("encrypted:")})
  private fun profile(url:String)=ServerProfile("srv","Server",url,allowCleartext=true)
  @Test fun originChangeCannotRetainSavedCookieInTheRealConnection()=runBlocking {    MockWebServer().use { a -> MockWebServer().use { b ->
      val store=store();val old=profile(a.url("/").toString());store.save(old,"old-password","private-cookie","opencode")
      val edited=old.copy(url=b.url("/").toString())
      val tested=profileCredentials(old.url,edited.url,store.credentials(old.id),"opencode","new-password")
      store.save(edited,tested.password,tested.cookie,tested.username)
      b.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      OpenCodeApi(store.profiles().single(),store.credentials("srv")).health()
      assertNull(b.takeRequest().getHeader("Cookie"));assertEquals("new-password",store.credentials("srv").password)
      // Defence in depth for a future caller that forgets to pass an explicit empty Cookie.
      store.save(old,"old-password","private-cookie","opencode");store.save(edited,"new-password",credentialUsername="opencode")
      assertEquals("",store.credentials("srv").cookie)
    }}
  }
  @Test fun interruptedProfileSaveCannotExposeNewCredentialsToOldOrigin() {
    val prefs=memoryPreferences();val secrets=memoryPreferences();val s=store(prefs,secrets)
    s.save(profile("https://old.example"),"old-password")
    val oldProfiles=prefs.getString("profiles",null)
    s.save(profile("https://new.example"),"new-password","new-cookie")
    // Simulate death after the credential commit but before the profile commit.
    prefs.edit().putString("profiles",oldProfiles).commit()
    assertEquals("",store(prefs,secrets).credentials("srv").password)
    assertEquals("",store(prefs,secrets).credentials("srv").cookie)
  }
  @Test fun unchangedOriginCanSaveCookieOnlyAccount() {
    val s=store();val p=profile("https://server.example");s.save(p,"","cookie","user")
    val c=profileCredentials(p.url,p.url,s.credentials(p.id),"user",null)
    s.save(p.copy(name="Renamed"),c.password,c.cookie,c.username)
    assertEquals("cookie",s.credentials(p.id).cookie);assertEquals("",s.credentials(p.id).password)
  }
  @Test fun deletionDuringEncryptionCannotResurrectANewCacheKey()=runBlocking {
    val p=memoryPreferences();val entered=CountDownLatch(1);val release=CountDownLatch(1)
    val cache=OfflineCache(p,{entered.countDown();check(release.await(3,TimeUnit.SECONDS));it},{it})
    cache.saveMessages("server","session",listOf(Message("m","user",0,emptyList())))
    assertTrue(entered.await(2,TimeUnit.SECONDS));cache.delete("server");release.countDown();cache.awaitWrites()
    assertFalse(p.contains("messages:server:session"))
  }
  @Test fun newerWriteWinsWhenOldEncryptionFinishesLate()=runBlocking {
    val p=memoryPreferences();val entered=CountDownLatch(1);val release=CountDownLatch(1);var first=true
    val cache=OfflineCache(p,{if(first){first=false;entered.countDown();check(release.await(3,TimeUnit.SECONDS))};it},{it})
    cache.saveMessages("s","t",listOf(Message("old","user",0,emptyList())))
    assertTrue(entered.await(2,TimeUnit.SECONDS));cache.deleteMessages("s","t")
    cache.saveMessages("s","t",listOf(Message("new","user",0,emptyList())))
    release.countDown();cache.awaitWrites();assertEquals("new",cache.messages("s","t").single().id)
  }
  private fun api(s:MockWebServer)=OpenCodeApi(profile(s.url("/").toString()),"fixture")
  @Test fun missingPermissionEndpointIsAnErrorNotAuthoritativeEmpty()=runBlocking {
    MockWebServer().use { s -> s.enqueue(MockResponse().setResponseCode(404));assertTrue(runCatching{api(s).sessionPermissions(Session("ses_a","/repo","T",0))}.isFailure) }
  }
  @Test fun formValuesPreserveOptionValuesTypesAndVisibility() {
    val form=JSONObject("""{"id":"frm","sessionID":"s","fields":[{"key":"mode","type":"string","options":[{"value":"fast","label":"快速"}]},{"key":"count","type":"integer","minimum":1,"maximum":3,"required":true},{"key":"hidden","type":"string","required":true,"when":[{"key":"mode","op":"eq","value":"slow"}]}]}""").toForm("/repo")
    assertEquals("fast",form.questions[0].options.single().value)
    val answer=formAnswer(form,listOf(listOf("fast"),listOf("2"),emptyList()))
    assertEquals(2L,answer.get("count"));assertFalse(answer.has("hidden"))
    assertTrue(runCatching{formAnswer(form,listOf(listOf("fast"),listOf("2.5"),emptyList()))}.isFailure)
  }

  @Test fun formDefaultsAndPatternsTolerateNullsAndInvalidRegex() {
    val promptWithNullDefault = QuestionPrompt("Title", emptyList(), false, true, """{"key":"k","type":"string","default":null}""")
    assertTrue(promptWithNullDefault.defaultAnswers().isEmpty())
    val promptWithArrayNulls = QuestionPrompt("Title", emptyList(), true, false, """{"key":"k","type":"multiselect","default":["a",null,2]}""")
    assertEquals(listOf("a", "2"), promptWithArrayNulls.defaultAnswers())
    val badPatternForm = JSONObject("""{"id":"frm","sessionID":"s","fields":[{"key":"k","type":"string","pattern":"["}]}""").toForm("/repo")
    assertTrue(runCatching { formAnswer(badPatternForm, listOf(listOf("abc"))) }.isFailure)
  }
  @Test fun legacyIslandVendorKeysAreIgnoredAndDroppedOnSave() {
    val p=memoryPreferences();val sec=memoryPreferences()
    // 旧版本曾在服务器资料 JSON 里保存厂商通道开关；升级后必须能正常读取，并在下次保存时丢弃这些键。
    p.edit().putString("profiles","""[{"id":"srv","name":"Server","url":"https://x","username":"opencode","autoConnect":true,"notifications":true,"allowCleartext":false,"islandHonor":true,"islandOppoFluidCloud":true}]""").commit()
    val store=store(p,sec)
    val loaded=store.profiles().single()
    assertEquals("https://x",loaded.url)
    store.save(loaded,"secret-password",credentialUsername="opencode")
    val raw=p.getString("profiles","")!!
    assertFalse(raw.contains("islandHonor"));assertFalse(raw.contains("islandOppoFluidCloud"))
  }
  @Test fun repeatedCursorFailsInsteadOfReturningPartialAuthoritativeData()=runBlocking {
    MockWebServer().use { s ->
      repeat(2){s.enqueue(MockResponse().setBody("""{"data":[],"cursor":{"next":"same"}}"""))}
      assertTrue(runCatching{api(s).children(Session("p","/repo","P",0))}.exceptionOrNull() is IOException)
    }
  }
  @Test fun sseOverflowTriggersObservableFailure()=runBlocking {
    MockWebServer().use { s ->
      s.enqueue(MockResponse().addHeader("Content-Type","text/event-stream").setBody(buildString{repeat(201){append("id: evt_$it\ndata: {\"id\":\"evt_$it\",\"type\":\"session.text.delta\",\"data\":{\"sessionID\":\"s\",\"assistantMessageID\":\"msg_a\",\"ordinal\":0,\"delta\":\"x\"}}\n\n")}}))
      val failure=runCatching{withTimeout(5000){api(s).events().collect{delay(10)}}}.exceptionOrNull()
      assertTrue(failure is IOException)
    }
  }

  @Test fun localProjectsAreDedupedByCanonicalDirectoryAndSurviveReload() {
    val p=memoryPreferences();val s=store(p)
    s.addLocalProject("srv", Project("p","/repo","Repo", sandboxes = listOf("/trees/task")))
    // The same canonical folder under a different id must replace, never duplicate.
    s.addLocalProject("srv", Project("p2","/repo/","Repo again", sandboxes = listOf("/trees/task")))
    assertEquals(listOf("/repo"), s.localProjects("srv").map { it.directory }.map(::normalizedDirectory).distinct())
    assertEquals(1, s.localProjects("srv").size)
    assertEquals("p2", s.localProjects("srv").single().id)
    assertEquals(listOf("/trees/task"), s.localProjects("srv").single().sandboxes)
    val fresh=ServerStore(p,memoryPreferences(),{"encrypted:$it"},{require(it.startsWith("encrypted:"));it.removePrefix("encrypted:")})
    assertEquals("p2", fresh.localProjects("srv").single().id)
    assertEquals(listOf("/trees/task"), fresh.localProjects("srv").single().sandboxes)
    s.removeLocalProject("srv","p2")
    assertTrue(s.localProjects("srv").isEmpty())
  }

  @Test fun modelVisibilityAndVariantsAreLocalPerServerSelections() {
    val p = memoryPreferences()
    val s = store(p)
    assertFalse(s.modelVisibilityConfigured("srv"))
    s.rememberModelOverrides("srv", mapOf("p/a" to true, "p/b" to false))
    assertTrue(s.modelVisibilityConfigured("srv"))
    assertEquals(mapOf("p/a" to true, "p/b" to false), s.modelOverrides("srv"))
    s.rememberModelVariant("srv", "p/a", "max")
    assertEquals(mapOf("p/a" to "max"), s.modelVariants("srv"))
    s.rememberModelVariant("srv", "p/a", null)
    assertTrue(s.modelVariants("srv").isEmpty())
    s.clearModelVisibility("srv")
    assertFalse(s.modelVisibilityConfigured("srv"))
    assertTrue(s.modelOverrides("srv").isEmpty())
  }

  @Test fun migrationClearsOnlyTheOldServerDerivedProjectSelection() {
    val p=memoryPreferences()
    p.edit().putString("selectedProject","old").putString("location:srv:project","old")
      .putString("location:srv:session","keep").putString("scope:srv","old").putString("directories:srv","[]").apply()
    val s=store(p)
    s.migrateLocalProjects()
    assertNull(p.getString("selectedProject",null))
    assertNull(p.getString("location:srv:project",null))
    assertEquals("keep",p.getString("location:srv:session",null))
    assertNull(p.getString("scope:srv",null))
    // A second run is a no-op and never touches the (now device-local) registrations.
    s.migrateLocalProjects()
    assertEquals(2, p.getInt("projectCatalogVersion",0))
  }
}
