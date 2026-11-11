package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTextProjectionTest {
  @Test fun hidesOscIncludingClipboardAndHyperlinkPayloads() {
    val raw = "start\u001b]52;c;c2VjcmV0\u0007\u001b]8;;https://secret.invalid\u001b\\link\u001b]8;;\u001b\\end"
    assertEquals("startlinkend", TerminalTextProjection.project(raw))
    assertEquals("safe", TerminalTextProjection.project("safe\u001b]52;c;unterminated-secret"))
  }

  @Test fun removesColorsAndApproximatesLineAndScreenErase() {
    assertEquals("red plain", TerminalTextProjection.project("\u001b[31mred\u001b[0m plain"))
    assertEquals("ok", TerminalTextProjection.project("old text\r\u001b[2Kok"))
    assertEquals("new", TerminalTextProjection.project("old\n\u001b[2Jnew"))
    assertEquals("done", TerminalTextProjection.project("progress\rdo\u001b[Kne"))
  }

  @Test fun carriageReturnAndBackspaceOverwriteWithoutSplittingCodePoints() {
    assertEquals("20%", TerminalTextProjection.project("10%\r20%"))
    assertEquals("abZ", TerminalTextProjection.project("abc\bZ"))
    assertEquals("A好", TerminalTextProjection.project("A😀\b好"))
    assertEquals("shortail\nnext", TerminalTextProjection.project("longtail\rshort\nnext"))
  }

  @Test fun handlesTruncatedEscapesAndMalformedUtf16Safely() {
    assertEquals("before", TerminalTextProjection.project("before\u001b[31"))
    assertEquals("before", TerminalTextProjection.project("before\u001b"))
    assertEquals("汉😀字\ufffd!\ufffd", TerminalTextProjection.project("汉😀字\ud800!\udc00"))
    assertFalse(TerminalTextProjection.project("a\u001b(Bb\u001bPprivate\u001b\\c").contains('\u001b'))
  }

  @Test fun stripsControlsAndExpandsTabsWithoutExecutableEscapes() {
    assertEquals("ab      c\nd", TerminalTextProjection.project("a\u0000\u0007\u0011b\t\u007fc\nd"))
    assertEquals("ok", TerminalTextProjection.project("\u009d52;c;hidden\u009cok"))
    assertEquals("x", TerminalTextProjection.project("\u009b32mx\u009b0m"))
  }

  @Test fun boundsTailAndNeverReturnsHalfASurrogatePair() {
    assertEquals("def", TerminalTextProjection.project("abcdef", 3))
    assertEquals("", TerminalTextProjection.project("😀", 1))
    assertEquals("😀", TerminalTextProjection.project("a😀", 2))
    assertEquals("", TerminalTextProjection.project("anything", 0))
    val output = TerminalTextProjection.project("a".repeat(100_000) + "😀end", 32)
    assertTrue(output.length <= 32)
    assertTrue(output.endsWith("😀end"))
    assertFalse(output.first().isLowSurrogate())
  }
}
