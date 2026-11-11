package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DshAttachmentRequestTest {
  private fun ref() = JSONObject("""{"attachmentId":"img","mediaType":"image/png","bytes":4,"width":1,"height":1,"name":"a.png"}""")

  @Test fun fullReferenceMatchesNewOfficialSchemaAndLegacyMatchesOld() {
    OfficialDshContract.assertArgs("session/attachment", DshAttachmentRequest.args("s", ref(), true), "0.2.2-rc.1")
    OfficialDshContract.assertArgs("session/attachment", DshAttachmentRequest.args("s", ref(), false), "0.2.1-rc.5")
  }

  @Test fun incompleteReferenceIsNeverInventedForTheNewHost() {
    val incomplete = JSONObject().put("attachmentId", "img")
    assertFalse(DshAttachmentRequest.hasVerifiedReference(incomplete))
    assertTrue(runCatching { DshAttachmentRequest.args("s", incomplete, true) }.isFailure)
    assertEquals("img", DshAttachmentRequest.args("s", incomplete, false).getJSONObject("request").getString("attachmentId"))
  }

  @Test fun unknownReferenceFieldsCannotLeakIntoStrictNewRequest() {
    val ref = ref().put("unexpected", "value")
    assertTrue(runCatching { OfficialDshContract.assertArgs("session/attachment", DshAttachmentRequest.args("s", ref, true), "0.2.2-rc.1") }.isFailure)
  }
}
