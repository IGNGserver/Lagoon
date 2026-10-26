package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.Message
import com.igng.opencode.lagoon.core.MessagePart
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptDiffTest {
  private fun assistant(id: String, text: String, created: Long = 1, tool: MessagePart? = null) =
    Message(id, "assistant", created, listOfNotNull(MessagePart("$id:text:0", "text", text = text), tool))

  private fun ops(batch: JSONObject?) = batch!!.getJSONArray("ops").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }

  @Test fun identicalTranscriptsProduceNothing() {
    val list = listOf(assistant("m1", "hello"))
    assertNull(TranscriptDiff.ops(list, list))
    assertNull(TranscriptDiff.ops(list, list.map { it.copy() }))
  }

  @Test fun streamingTextTravelsAsAnAppendedSuffix() {
    val ops = ops(TranscriptDiff.ops(listOf(assistant("m1", "Hel")), listOf(assistant("m1", "Hello"))))
    assertEquals(1, ops.size)
    assertEquals("append", ops[0].getString("op"))
    assertEquals("text", ops[0].getString("field"))
    assertEquals(0, ops[0].getInt("index"))
    assertEquals("lo", ops[0].getString("text"))
  }

  @Test fun aRewrittenPartIsSentWhole() {
    val ops = ops(TranscriptDiff.ops(listOf(assistant("m1", "draft")), listOf(assistant("m1", "final"))))
    assertEquals("part", ops.single().getString("op"))
    assertEquals("final", ops.single().getJSONObject("part").getString("text"))
  }

  @Test fun aToolStatusChangeIsAPartUpdateNotAnAppend() {
    val running = MessagePart("call_1", "tool", tool = "shell", status = "running", input = "{}")
    val done = running.copy(status = "completed", output = "ok")
    val ops = ops(TranscriptDiff.ops(listOf(assistant("m1", "x", tool = running)), listOf(assistant("m1", "x", tool = done))))
    assertEquals("part", ops.single().getString("op"))
    assertEquals(1, ops.single().getInt("index"))
  }

  @Test fun newPartsOrHeaderChangesReplaceTheMessage() {
    val before = assistant("m1", "x")
    val withTool = before.copy(parts = before.parts + MessagePart("call_1", "tool", tool = "read"))
    assertEquals("put", ops(TranscriptDiff.ops(listOf(before), listOf(withTool))).single().getString("op"))
    val completed = before.copy(completedAt = 5, finish = "stop")
    assertEquals("put", ops(TranscriptDiff.ops(listOf(before), listOf(completed))).single().getString("op"))
  }

  @Test fun deletionsAndAdditionsKeepTheImpliedOrder() {
    val batch = TranscriptDiff.ops(listOf(assistant("a", "1"), assistant("b", "2")), listOf(assistant("b", "2"), assistant("c", "3")))!!
    val ops = ops(batch)
    assertTrue(ops.any { it.getString("op") == "put" && it.getJSONObject("message").getString("id") == "c" })
    assertTrue(ops.any { it.getString("op") == "del" && it.getString("id") == "a" })
    assertFalse(batch.has("order"))
  }

  @Test fun aMovedMessageCarriesTheFullOrder() {
    // Inbox delivery moves the input to the end of the timeline.
    val queued = Message("in_1", "user", 1, listOf(MessagePart("in_1:text", "text", text = "next")), queued = true)
    val reply = assistant("m1", "answer", created = 2)
    val delivered = queued.copy(created = 3, queued = false)
    val batch = TranscriptDiff.ops(listOf(queued, reply), listOf(reply, delivered))!!
    assertEquals(listOf("m1", "in_1"), batch.getJSONArray("order").let { a -> (0 until a.length()).map(a::getString) })
  }
}
