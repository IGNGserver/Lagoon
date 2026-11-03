package com.igng.opencode.lagoon.core

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Protocol v1 framing inside an authenticated Noise channel; one instance per direction/connection. */
internal class RemoteSecureFragments {
  private data class Assembly(val total: Int, val size: Int, val chunks: MutableList<ByteArray>, var received: Int = 0)
  private val assemblies = mutableMapOf<Long, Assembly>()
  private val nextId = AtomicLong(1)

  @Synchronized fun encode(message: ByteArray): List<ByteArray> {
    if (message.size > MAX_MESSAGE_BYTES) throw IOException("Remote 消息超过上限")
    if (message.size <= CHUNK_BYTES) return listOf(message.copyOf())
    val id = nextId.getAndUpdate { if (it == 0xffff_ffffL) 1 else it + 1 }
    val count = (message.size + CHUNK_BYTES - 1) / CHUNK_BYTES
    return (0 until count).map { index ->
      val offset = index * CHUNK_BYTES
      val length = minOf(CHUNK_BYTES, message.size - offset)
      ByteBuffer.allocate(HEADER_BYTES + length).put(MAGIC).put(1).putInt(id.toInt())
        .putShort(index.toShort()).putShort(count.toShort()).putInt(message.size).put(message, offset, length).array()
    }
  }

  /** Null means a valid fragment has been accepted but the message is not yet complete. */
  @Synchronized fun decode(frame: ByteArray): ByteArray? {
    if (frame.size < MAGIC.size || !frame.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
      if (frame.size > CHUNK_BYTES) throw IOException("Remote 明文帧超过上限")
      return frame.copyOf()
    }
    if (frame.size < HEADER_BYTES) throw IOException("Remote 分片头无效")
    val input = ByteBuffer.wrap(frame)
    input.position(MAGIC.size)
    val version = input.get().toInt() and 0xff
    val id = input.int.toLong() and 0xffff_ffffL
    val index = input.short.toInt() and 0xffff
    val total = input.short.toInt() and 0xffff
    val size = input.int
    if (version != 1 || id == 0L || size <= CHUNK_BYTES || size > MAX_MESSAGE_BYTES || total < 2 ||
      index >= total || total != (size + CHUNK_BYTES - 1) / CHUNK_BYTES ||
      frame.size - HEADER_BYTES != minOf(CHUNK_BYTES, size - index * CHUNK_BYTES)) throw IOException("Remote 分片元数据无效")
    val assembly = assemblies[id] ?: run {
      if (index != 0 || assemblies.size >= MAX_ASSEMBLIES) throw IOException("Remote 分片顺序无效")
      Assembly(total, size, mutableListOf()).also { assemblies[id] = it }
    }
    if (assembly.total != total || assembly.size != size || assembly.chunks.size != index) {
      assemblies.remove(id)
      throw IOException("Remote 分片顺序无效")
    }
    val chunk = frame.copyOfRange(HEADER_BYTES, frame.size)
    assembly.chunks += chunk
    assembly.received += chunk.size
    if (assembly.chunks.size < total) return null
    assemblies.remove(id)
    if (assembly.received != size) throw IOException("Remote 消息长度无效")
    val output = ByteArray(size)
    var offset = 0
    assembly.chunks.forEach { it.copyInto(output, offset); offset += it.size }
    return output
  }

  @Synchronized fun reset() { assemblies.clear(); nextId.set(1) }

  companion object {
    internal const val CHUNK_BYTES = 48 * 1024
    internal const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
    private const val HEADER_BYTES = 17
    private const val MAX_ASSEMBLIES = 8
    private val MAGIC = byteArrayOf(0x44, 0x53, 0x48, 0x46)
  }
}
