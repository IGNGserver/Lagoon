package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.DshApi
import com.igng.opencode.lagoon.core.HarnessClient
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SshDshClient` is a hand-written forwarding layer because one SSH binding must survive DSH
 * generations (each generation is a new `DshApi`). That design silently dropped capabilities
 * before: `searchSessionContent`, `serverWorkspaces` and `forkSession(boundary)` were implemented
 * by [DshApi] but never forwarded, so over SSH the UI offered features that returned null or 501.
 *
 * This guard compares the two classes by reflection: every `HarnessClient` method that [DshApi]
 * specializes must also be declared by [SshDshClient]. Adding a method to `HarnessClient`, or an
 * override to `DshApi`, fails here until the SSH layer forwards it.
 */
class SshClientForwardingTest {
  @Test fun sshClientForwardsEveryMethodDshApiSpecializes() {
    val interfaceMethods = HarnessClient::class.java.methods
    val sshMethods = SshDshClient::class.java.declaredMethods
      .map { it.name to it.parameterTypes.toList() }
      .toSet()
    val missing = interfaceMethods.filter { method ->
      val parameters = method.parameterTypes.toList()
      val specialized = runCatching {
        DshApi::class.java.getMethod(method.name, *method.parameterTypes).declaringClass
      }.getOrNull()?.let { it != HarnessClient::class.java } == true
      specialized && (method.name to parameters) !in sshMethods
    }.map { it.name }
    assertTrue(
      "SSH DSH client does not forward: ${missing.sorted().joinToString()}",
      missing.isEmpty()
    )
    // Guard against a vacuous pass: the reflection must see the optional capabilities it protects.
    val specialized = interfaceMethods.count { method ->
      runCatching { DshApi::class.java.getMethod(method.name, *method.parameterTypes).declaringClass }
        .getOrNull() != HarnessClient::class.java
    }
    assertTrue("reflection found no DshApi specialization to compare", specialized >= 10)
  }
}
