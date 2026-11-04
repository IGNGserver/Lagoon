package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemoteSecureFragmentsTest {
  @Test fun unfragmentedAndFragmentedMessagesRoundTrip() {
    val sender = RemoteSecureFragments()
    val receiver = RemoteSecureFragments()
    for (size in listOf(0, 1, 48 * 1024, 48 * 1024 + 1, 4 * 1024 * 1024)) {
      val input = ByteArray(size) { (it % 251).toByte() }
      val frames = sender.encode(input)
      var decoded: ByteArray? = null
      frames.forEach { decoded = receiver.decode(it) }
      assertArrayEquals(input, decoded)
    }
  }

  @Test fun rejectsMissingDuplicateAndCorruptedFragments() {
    val frames = RemoteSecureFragments().encode(ByteArray(100_000) { it.toByte() })
    try { RemoteSecureFragments().decode(frames[1]); fail("expected failure") } catch (_: IOException) { }
    val receiver = RemoteSecureFragments()
    assertNull(receiver.decode(frames[0]))
    try { receiver.decode(frames[0]); fail("expected failure") } catch (_: IOException) { }
    val broken = frames[0].copyOf().apply { this[4] = 2 }
    try { RemoteSecureFragments().decode(broken); fail("expected failure") } catch (_: IOException) { }
    try { RemoteSecureFragments().decode(frames[0].copyOf(18)); fail("expected failure") } catch (_: IOException) { }
  }

  @Test fun limitsParallelAssembliesAndResetsState() {
    val sender = RemoteSecureFragments()
    val receiver = RemoteSecureFragments()
    val frames = (1..9).map { sender.encode(ByteArray(50_000)) }
    frames.take(8).forEach { assertNull(receiver.decode(it[0])) }
    try { receiver.decode(frames[8][0]); fail("expected failure") } catch (_: IOException) { }
    receiver.reset()
    assertNull(receiver.decode(frames[8][0]))
    assertArrayEquals(ByteArray(50_000), receiver.decode(frames[8][1]))
    try { sender.encode(ByteArray(4 * 1024 * 1024 + 1)); fail("expected failure") } catch (_: IOException) { }
  }
}
