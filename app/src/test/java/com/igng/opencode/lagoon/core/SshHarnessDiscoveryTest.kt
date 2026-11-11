package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SshHarnessDiscoveryTest {
  private val path = "/home/dev/.local/state/opencode/service.json"
  private val valid = """{"id":"generation-1","version":"2.2.6","url":"http://127.0.0.1:4567/","pid":123,"password":"test-secret"}"""

  @Test fun acceptsOfficialRegistrationAndNeverTreatsGenerationAsStableIdentity() {
    val first = requireNotNull(OpenCodeSshRegistration.parse(path, valid))
    val restarted = requireNotNull(OpenCodeSshRegistration.parse(path,
      valid.replace("generation-1", "generation-2").replace(":123", ":124").replace(":4567/", ":4568/")))
    assertEquals("127.0.0.1", first.host)
    assertEquals(4567, first.port)
    assertEquals(123, first.pid)
    assertEquals("test-secret", first.password)
    assertNotEquals(first.generationId, restarted.generationId)
    assertEquals(first.stableLocator("ssh-1"), restarted.stableLocator("ssh-1"))
    assertNotEquals(first.stableLocator("ssh-1"), first.stableLocator("ssh-2"))
    assertNotEquals(first.stableLocator("ssh-1"), sshHarnessLocator("ssh-1", "$path.other"))
    assertFalse(first.toString().contains("test-secret"))
  }

  @Test fun optionalFieldsMayBeAbsentAndIpv6IsAnExactLoopbackLiteral() {
    val value = requireNotNull(OpenCodeSshRegistration.parse(path, """{"url":"http://[::1]:1234","pid":1}"""))
    assertEquals("::1", value.host)
    assertNull(value.version)
    assertNull(value.generationId)
    assertNull(value.password)
    assertNotNull(OpenCodeSshRegistration.parse(path, """{"url":"http://localhost:1234/","pid":1}"""))
  }

  @Test fun rejectsCoercionDuplicateFieldsAndNonJsonSyntax() {
    listOf(
      valid.replace("\"pid\":123", "\"pid\":\"123\""),
      valid.replace("\"pid\":123", "\"pid\":0"),
      valid.replace("\"pid\":123", "\"pid\":-1"),
      valid.replace("\"pid\":123", "\"pid\":1.0"),
      valid.replace("\"pid\":123", "\"pid\":1e2"),
      valid.replace("\"pid\":123", "\"pid\":2147483648"),
      valid.replace("\"pid\":123", "\"pid\":0123"),
      valid.replace("\"version\":\"2.2.6\"", "\"version\":2"),
      valid.replace("\"password\":\"test-secret\"", "\"password\":null"),
      valid.replace("test-secret", ""),
      valid.replace("test-secret", "a\\nb"),
      valid.replace("\"id\":\"generation-1\"", "\"id\":\"\""),
      valid.replace("\"pid\":123", "\"pid\":123,\"pid\":456"),
      valid.replace("\"id\"", "id"),
      valid.replace('"', '\''),
      valid.dropLast(1) + ",}",
      valid.dropLast(1) + ",\"unknown\":1}",
      "$valid trailing",
      "[$valid]"
    ).forEach { assertNull("Should reject invalid registration", OpenCodeSshRegistration.parse(path, it)) }
    assertNull(OpenCodeSshRegistration.parse(path, " ".repeat(OpenCodeSshRegistration.MAX_BYTES) + valid))
    assertNull(OpenCodeSshRegistration.parse("relative/service.json", valid))
  }

  @Test fun rejectsPublicAndAmbiguousEndpointsRatherThanDoingDnsOrFollowingPaths() {
    listOf(
      "https://127.0.0.1:4567/", "http://127.0.0.1/", "http://127.0.0.1:0/",
      "http://127.0.0.1:65536/", "http://10.0.0.1:4567/", "http://example.com:4567/",
      "http://127.0.0.2:4567/", "http://127.1:4567/", "http://2130706433:4567/",
      "http://127.0.0.1.nip.io:4567/", "http://localhost.evil:4567/",
      "http://user:pw@127.0.0.1:4567/", "http://127.0.0.1:4567/api/",
      "http://127.0.0.1:4567/?token=x", "http://127.0.0.1:4567/#x",
      "http://0.0.0.0:4567/", "http://[::]:4567/", "http://[::ffff:127.0.0.1]:4567/"
    ).forEach { url -> assertNull(url, OpenCodeSshRegistration.parse(path,
      valid.replace("http://127.0.0.1:4567/", url))) }
  }

  @Test fun infoMustMatchPidAndRegistrationVersionAndCannotProveAuthentication() {
    val registration = requireNotNull(OpenCodeSshRegistration.parse(path, valid))
    val info = JSONObject().put("pid", 123).put("version", "2.2.6")
    val publicStatus = registration.validateInfo(200, info, observedAtMs = 100)
    assertEquals(DiscoveryValue.YES, publicStatus.compatible)
    assertEquals(DiscoveryValue.YES, publicStatus.listening)
    assertEquals(DiscoveryValue.UNKNOWN, publicStatus.authenticated)
    assertEquals(DiscoveryValue.UNKNOWN, publicStatus.available)
    val authenticated = registration.validateInfo(200, info, authenticated = true, observedAtMs = 100)
    assertTrue(authenticated.availableNow(100))
    assertFalse(authenticated.availableNow(30_101))
    listOf(JSONObject().put("pid", 124).put("version", "2.2.6"),
      JSONObject().put("pid", 123).put("version", "2.2.5"),
      JSONObject().put("pid", "123").put("version", "2.2.6"),
      JSONObject().put("pid", 123.0).put("version", "2.2.6"), JSONObject()).forEach { mismatched ->
      val status = registration.validateInfo(200, mismatched, authenticated = true, observedAtMs = 100)
      assertEquals(DiscoveryValue.NO, status.compatible)
      assertEquals(DiscoveryValue.NO, status.available)
    }
  }

  @Test fun httpFailuresNeverBecomeAvailableAndDoNotEchoResponseContents() {
    val registration = requireNotNull(OpenCodeSshRegistration.parse(path, valid))
    val secret = JSONObject().put("password", "test-secret")
    val unauthorized = registration.validateInfo(401, secret, authenticated = true, observedAtMs = 100)
    assertEquals(DiscoveryValue.NO, unauthorized.authenticated)
    assertEquals(DiscoveryValue.NO, unauthorized.available)
    assertFalse(unauthorized.toString().contains("test-secret"))
    val redirect = registration.validateInfo(302, secret, authenticated = true, observedAtMs = 100)
    assertEquals(DiscoveryValue.NO, redirect.compatible)
    val unreachable = registration.validateInfo(null, null, observedAtMs = 100)
    assertEquals(DiscoveryCompleteness.PARTIAL, unreachable.completeness)
    assertEquals(DiscoveryValue.UNKNOWN, unreachable.available)
  }
}
