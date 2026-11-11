package com.igng.opencode.lagoon.core

/**
 * Stateless, bounded plain-text view of a terminal's complete retained raw replay.
 * Not a VT emulator: colors, cursor positioning, alternate screens and interactive TUIs are not
 * reproduced. OSC (including clipboard OSC 52) and other escape-string payloads are never exposed.
 * CR / BS overwrite the current line; common line/screen erases are approximated.
 */
object TerminalTextProjection {
  fun project(raw: String, maxChars: Int = 65_536): String {
    val limit = maxChars.coerceIn(0, 262_144)
    if (limit == 0) return ""
    val text = StringBuilder()
    var cursor = 0
    var lineStart = 0
    var index = 0

    fun trim() {
      if (text.length <= limit) return
      var cut = text.length - limit
      if (cut < text.length && Character.isLowSurrogate(text[cut]) && cut > 0 && Character.isHighSurrogate(text[cut - 1])) cut++
      text.delete(0, cut)
      cursor = (cursor - cut).coerceAtLeast(0)
      lineStart = (lineStart - cut).coerceAtLeast(0)
    }
    fun put(codePoint: Int) {
      val value = String(Character.toChars(codePoint))
      if (cursor < text.length) {
        val end = cursor + Character.charCount(Character.codePointAt(text, cursor))
        text.replace(cursor, end, value)
      } else text.append(value)
      cursor += value.length
      // Amortize prefix removal; working memory is bounded even for a very large input replay.
      if (text.length > limit + 4096) trim()
    }
    fun stringEnd(start: Int, bell: Boolean): Int {
      var at = start
      while (at < raw.length) {
        if ((bell && raw[at] == '\u0007') || raw[at] == '\u009c') return at + 1
        if (raw[at] == '\u001b' && at + 1 < raw.length && raw[at + 1] == '\\') return at + 2
        at++
      }
      return raw.length // Unterminated strings stay hidden, not printed as content.
    }
    fun csi(start: Int): Int {
      var at = start
      while (at < raw.length && raw[at].code !in 0x40..0x7e) at++
      if (at == raw.length) return at
      val args = raw.substring(start, at)
      val mode = args.takeIf { it.all(Char::isDigit) }?.toIntOrNull() ?: 0
      when (raw[at]) {
        'K' -> when (mode) {
          0 -> text.delete(cursor, text.length)
          1 -> { for (i in lineStart until cursor) text.setCharAt(i, ' ') }
          2 -> { text.delete(lineStart, text.length); cursor = lineStart }
        }
        'J' -> when (mode) {
          0 -> text.delete(cursor, text.length)
          2, 3 -> { text.clear(); cursor = 0; lineStart = 0 }
        }
      }
      return at + 1
    }
    while (index < raw.length) {
      val char = raw[index++]
      when {
        char == '\u001b' -> {
          if (index >= raw.length) continue
          when (val next = raw[index++]) {
            '[' -> index = csi(index)
            ']', 'P', 'X', '^', '_' -> index = stringEnd(index, next == ']')
            'c' -> { text.clear(); cursor = 0; lineStart = 0 }
            else -> {
              // ESC intermediate-byte sequences, e.g. charset selection ESC ( B.
              if (next.code in 0x20..0x2f) {
                while (index < raw.length && raw[index].code in 0x20..0x2f) index++
                if (index < raw.length && raw[index].code in 0x30..0x7e) index++
              }
            }
          }
        }
        char == '\u009b' -> index = csi(index)
        char == '\u009d' -> index = stringEnd(index, true)
        char == '\u0090' || char == '\u0098' || char == '\u009e' || char == '\u009f' -> index = stringEnd(index, false)
        char == '\r' -> cursor = lineStart
        char == '\b' -> if (cursor > lineStart) cursor = Character.offsetByCodePoints(text, cursor, -1)
        char == '\n' -> { text.append('\n'); cursor = text.length; lineStart = cursor; if (text.length > limit + 4096) trim() }
        char == '\t' -> repeat(8 - ((cursor - lineStart) % 8)) { put(' '.code) }
        char.code < 0x20 || char.code in 0x7f..0x9f -> Unit
        Character.isHighSurrogate(char) -> {
          if (index < raw.length && Character.isLowSurrogate(raw[index])) put(Character.toCodePoint(char, raw[index++]))
          else put(0xfffd)
        }
        Character.isLowSurrogate(char) -> put(0xfffd)
        else -> put(char.code)
      }
    }
    trim()
    return text.toString()
  }
}
