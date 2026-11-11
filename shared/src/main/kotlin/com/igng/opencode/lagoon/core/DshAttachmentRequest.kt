package com.igng.opencode.lagoon.core

import org.json.JSONObject

/** Session/attachment changed from ID to verified reference in 0.2.2; this is a read-only wire seam. */
internal object DshAttachmentRequest {
  fun args(sessionId: String, attachment: JSONObject, fullReference: Boolean): JSONObject {
    val id = attachment.str("attachmentId")
    require(sessionId.isNotBlank() && id.isNotBlank()) { "Invalid attachment identity" }
    val request = JSONObject().put("sessionId", sessionId)
    if (!fullReference) return JSONObject().put("request", request.put("attachmentId", id))
    val ref = JSONObject(attachment.toString())
    require(ref.str("mediaType") in AttachmentPolicy.MODEL_IMAGE_TYPES &&
      (ref.opt("bytes") as? Number)?.toLong()?.let { it >= 0 } == true &&
      (ref.opt("width") as? Number)?.toInt()?.let { it > 0 } == true &&
      (ref.opt("height") as? Number)?.toInt()?.let { it > 0 } == true) { "Incomplete verified attachment reference" }
    return JSONObject().put("request", request.put("attachment", ref))
  }

  fun hasVerifiedReference(attachment: JSONObject): Boolean =
    runCatching { args("scope", attachment, fullReference = true) }.isSuccess
}
