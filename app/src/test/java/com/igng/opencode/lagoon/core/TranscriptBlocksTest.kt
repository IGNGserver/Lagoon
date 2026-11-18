package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptBlocksTest {
  @Test
  fun virtualizesParagraphsHeadingsTablesAndCodeBlocksIntoDistinctItems() {
    val md = """
      # Architecture Overview

      First paragraph explaining the 144Hz frame pipeline in detail.

      | Stage | Budget |
      | --- | --- |
      | Input | 1.5ms |
      | Draw | 2.0ms |

      ```kotlin
      fun frameBudgetMs(): Float = 1000f / 144f
      ```

      Final summary paragraph.
    """.trimIndent()

    val row = TranscriptRow(key = "answer:m1/#0", kind = "text", text = md)
    val items = TranscriptBlocks.expandMainRows(listOf(row))

    assertEquals(
      listOf("md-heading", "md-paragraph", "md-table", "md-code", "md-paragraph"),
      items.map { it.contentType }
    )
    assertEquals(
      listOf(
        "answer:m1/#0#b:0",
        "answer:m1/#0#b:1",
        "answer:m1/#0#b:2",
        "answer:m1/#0#b:3",
        "answer:m1/#0#b:4"
      ),
      items.map { it.key }
    )
    assertEquals(3, items[2].tableBlocks?.size)
    assertNotNull("Code fragments must be pre-computed off the UI thread", items[3].codeFragments)
  }

  @Test
  fun incrementalStreamingReusesSealedPrefixBlockInstancesAndMatchesFullParse() {
    val prefix = buildString {
      append("First paragraph with enough characters to cross the sealed boundary threshold cleanly.\n\n")
      append("```diff\n--- a/Main.kt\n+++ b/Main.kt\n+val hz = 144\n```\n\n")
      append("Second settled paragraph after the closed fenced code block.\n\n")
    }
    val step1 = prefix + "Streaming tail token 1"
    val step2 = prefix + "Streaming tail token 1 and token 2"

    val first = TranscriptBlocks.blocksFor("stream:1", step1)
    val second = TranscriptBlocks.blocksFor("stream:1", step2)
    val fullReference = MarkdownBlocks.parse(step2)

    assertEquals("Incremental parse must match full CommonMark parse", fullReference, second)
    assertTrue(first.size >= 4)
    // Settled prefix blocks must keep identical object references (===) so Compose skips recomposing them!
    assertSame(first[0], second[0])
    assertSame(first[1], second[1])
    assertSame(first[2], second[2])
  }

  @Test
  fun unclosedCodeFenceNeverSplitsAcrossInternalBlankLines() {
    val unclosed = buildString {
      append("Intro paragraph with sufficient text before an unclosed code block starts.\n\n")
      append("```python\ndef compute():\n\n    return 144\n\n# still inside fence\n")
    }
    val boundary = TranscriptBlocks.findLastSealedBoundary(unclosed)
    val blocks = TranscriptBlocks.blocksFor("unclosed:1", unclosed)
    assertEquals(MarkdownBlocks.parse(unclosed), blocks)
    assertTrue("Boundary must not land inside the unclosed fence", boundary <= unclosed.indexOf("```python"))
  }
}
