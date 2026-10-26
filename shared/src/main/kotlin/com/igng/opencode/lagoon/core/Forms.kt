package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.toForm(directory: String) = QuestionRequest(str("id"), str("sessionID"), directory,
  arr("fields").objects().map { f ->
    val options = if (f.str("type") == "boolean") listOf(QuestionOption("是", "", "true"), QuestionOption("否", "", "false"))
      else f.arr("options").objects().map { QuestionOption(it.str("label"), it.str("description"), it.str("value")) }
    QuestionPrompt(f.str("title").ifBlank { f.str("key") } + f.str("description").takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty(),
      options, f.str("type") == "multiselect", f.str("type") in setOf("string", "number", "integer") && (options.isEmpty() || f.optBoolean("custom")) || f.optBoolean("custom"), f.toString())
  }, form = true)

internal fun QuestionPrompt.defaultAnswers(): List<String> {
  if (field.isBlank()) return emptyList()
  val raw = runCatching { JSONObject(field).opt("default") }.getOrNull() ?: return emptyList()
  if (raw == JSONObject.NULL) return emptyList()
  return if (raw is JSONArray) (0 until raw.length()).mapNotNull { index ->
    raw.opt(index)?.takeIf { it != JSONObject.NULL }?.toString()
  } else listOf(raw.toString())
}
internal fun formVisible(field: JSONObject, values: Map<String, List<String>>): Boolean =
  !field.optBoolean("hidden") && field.arr("when").objects().all { condition ->
    val expected = condition.opt("value")
    val equal = values[condition.str("key")]?.any { value -> if (expected is Number) value.toDoubleOrNull() == expected.toDouble() else value == expected?.toString() } == true
    if (condition.str("op") == "neq") !equal else equal
  }
internal fun formAnswer(request: QuestionRequest, answers: List<List<String>>): JSONObject {
  require(answers.size == request.questions.size) { "表单字段已变化，请刷新" }
  val fields = request.questions.map { runCatching { JSONObject(it.field) }.getOrElse { JSONObject() } }
  val values = fields.mapIndexed { i, f -> f.str("key") to answers[i] }.toMap()
  return JSONObject().apply {
    fields.forEachIndexed { index, f ->
      if (!formVisible(f, values) || f.str("type") == "external") return@forEachIndexed
      val row = answers[index]; val label = f.str("title").ifBlank { f.str("key") }
      require(!f.optBoolean("required") || row.isNotEmpty() && row.any { it.isNotBlank() }) { "请填写：$label" }
      if (row.isEmpty()) return@forEachIndexed
      val text = row.first()
      val options = f.arr("options").objects().map { it.str("value") }
      require(options.isEmpty() || f.optBoolean("custom") || row.all { it in options }) { "选项无效：$label" }
      val value: Any = when (f.str("type")) {
        "string" -> {
          require(text.length >= f.optInt("minLength", 0) && text.length <= f.optInt("maxLength", Int.MAX_VALUE)) { "长度无效：$label" }
          if (f.has("pattern")) {
            val pattern = runCatching { Regex(f.str("pattern")) }.getOrElse { error("表单正则格式无效：$label") }
            require(pattern.containsMatchIn(text)) { "格式无效：$label" }
          }
          text
        }
        "boolean" -> { require(text == "true" || text == "false"); text.toBooleanStrict() }
        "number", "integer" -> {
          val number = text.toDoubleOrNull() ?: error("请输入数字：$label")
          require(number.isFinite() && number >= f.optDouble("minimum", -Double.MAX_VALUE) && number <= f.optDouble("maximum", Double.MAX_VALUE)) { "数值超出范围：$label" }
          if (f.str("type") == "integer") {
            require(number % 1.0 == 0.0 && number >= Long.MIN_VALUE.toDouble() && number <= Long.MAX_VALUE.toDouble()) { "请输入整数：$label" }
            number.toLong()
          } else number
        }
        "multiselect" -> { require(row.size >= f.optInt("minItems", 0) && row.size <= f.optInt("maxItems", Int.MAX_VALUE)) { "选择数量无效：$label" }; JSONArray(row) }
        else -> error("暂不支持此表单字段：$label")
      }
      put(f.str("key"), value)
    }
  }
}
