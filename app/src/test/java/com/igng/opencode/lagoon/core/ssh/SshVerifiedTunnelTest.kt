package com.igng.opencode.lagoon.core.ssh

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses only a scratch registration and a JVM-owned loopback socket, never a user's service. */
class SshVerifiedTunnelTest {
  private fun currentPid(): Int {
    val process = ProcessBuilder("/bin/sh", "-c", "echo \$PPID").start()
    return process.inputStream.bufferedReader().readLine().trim().toInt().also { process.waitFor() }
  }

  @Test fun registeredProcessOwnsTheSameSocketBeforeCredentialsAreForwarded() {
    assumeSupportedHost()
    LocalServer().use { server ->
      val target = registration(server.port, currentPid())
      val process = ProcessBuilder("sh", "-lc", SshVerifiedTunnel.command(target)).start()
      try {
        assertEquals("LAGOON_SOCKET_READY_V1", line(process.inputStream))
        val first = server.requests.poll(5, TimeUnit.SECONDS)
        assertNotNull(first)
        assertFalse(first!!.contains("Authorization:", ignoreCase = true))
        process.outputStream.write("GET /protected HTTP/1.1\r\nHost: localhost\r\nAuthorization: Basic fixture\r\n\r\n".toByteArray())
        process.outputStream.flush()
        assertEquals("HTTP/1.1 200 OK", line(process.inputStream))
        val second = server.requests.poll(5, TimeUnit.SECONDS)
        assertNotNull(second)
        assertTrue(second!!.contains("Authorization: Basic fixture"))
      } finally { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
    }
  }

  @Test fun dshGenerationIsVerifiedBeforeTokenBearingRequest() {
    assumeSupportedHost()
    val scratch = File(System.getenv("BUILD_SCRATCH_ROOT") ?: File(System.getProperty("user.home"), ".build").path)
    val root = File(scratch, "Lagoon/ssh-proof-fixtures").apply { mkdirs() }
    val home = Files.createTempDirectory(root.toPath(), "dsh-home-").toFile()
    try {
      val pid = currentPid()
      val proc = File("/proc/$pid/stat")
      val start = if (proc.isFile) proc.readText().substringAfterLast(") ").trim().split(Regex("\\s+"))[19]
      else ProcessBuilder("/bin/ps", "-p", pid.toString(), "-o", "lstart=").start().inputStream.bufferedReader().readText().trim()
      val state = File(home, ".lagoon-ssh-dsh").apply { mkdirs() }
      File(state, "web.pid").writeText("$pid\n")
      File(state, "web.st").writeText("$start\n")
      LocalServer().use { server ->
        val target = SshVerifiedTunnel.Target("127.0.0.1", server.port, pid, dshStateDirectory = ".lagoon-ssh-dsh")
        val process = ProcessBuilder("sh", "-c", SshVerifiedTunnel.command(target)).apply {
          environment()["HOME"] = home.absolutePath
        }.start()
        try {
          assertEquals("LAGOON_SOCKET_READY_V1", line(process.inputStream))
          val activation = server.requests.poll(5, TimeUnit.SECONDS)
          assertNotNull(activation)
          assertFalse(activation!!.contains("token=", ignoreCase = true))
          process.outputStream.write("GET /?token=fixture HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
          process.outputStream.flush()
          assertEquals("HTTP/1.1 200 OK", line(process.inputStream))
          assertTrue(server.requests.poll(5, TimeUnit.SECONDS)!!.contains("token=fixture"))
        } finally { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
      }
    } finally { home.deleteRecursively() }
  }

  @Test fun linuxIpv4MappedSocketIsVerifiedAgainstItsActualOwner() {
    assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
    val fixture = """
      import os, socket, sys
      try:
          listener=socket.socket(socket.AF_INET6,socket.SOCK_STREAM)
          listener.setsockopt(socket.IPPROTO_IPV6,socket.IPV6_V6ONLY,0)
          listener.bind(('::ffff:127.0.0.1',0)); listener.listen(1)
      except OSError as error:
          print('SKIP:'+str(error),flush=True); sys.exit(0)
      print(str(os.getpid())+' '+str(listener.getsockname()[1]),flush=True)
      client,_=listener.accept()
      for _ in range(2):
          request=b''
          while b'\r\n\r\n' not in request:
              block=client.recv(4096)
              if not block: sys.exit(0)
              request+=block
          client.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n')
    """.trimIndent()
    val server = ProcessBuilder("python3", "-u", "-c", fixture).redirectErrorStream(true).start()
    try {
      val greeting = server.inputStream.bufferedReader().readLine().orEmpty()
      assumeTrue("IPv4-mapped loopback is unavailable: $greeting", !greeting.startsWith("SKIP:"))
      val values = greeting.split(' ')
      require(values.size == 2) { "Cannot start mapped socket fixture: $greeting" }
      val target = registration(values[1].toInt(), values[0].toInt())
      val helper = ProcessBuilder("sh", "-c", SshVerifiedTunnel.command(target)).start()
      try {
        assertEquals("LAGOON_SOCKET_READY_V1", line(helper.inputStream))
        helper.outputStream.write("GET /protected HTTP/1.1\r\nHost: localhost\r\nAuthorization: Basic fixture\r\n\r\n".toByteArray())
        helper.outputStream.flush()
        assertEquals("HTTP/1.1 200 OK", line(helper.inputStream))
      } finally {
        helper.destroyForcibly(); helper.waitFor(5, TimeUnit.SECONDS)
        target.registrationPath?.let { File(it).parentFile?.deleteRecursively() }
      }
    } finally { server.destroyForcibly(); server.waitFor(5, TimeUnit.SECONDS) }
  }

  @Test fun stalePidNeverReceivesCredentialsEvenWhenBytesAreAlreadyQueued() {
    assumeSupportedHost()
    val unrelated = ProcessBuilder("sh", "-c", "echo \$\$; exec sleep 20").start()
    val unrelatedPid = unrelated.inputStream.bufferedReader().readLine().trim().toInt()
    try {
      LocalServer().use { server ->
        val target = registration(server.port, unrelatedPid)
        val process = ProcessBuilder("sh", "-lc", SshVerifiedTunnel.command(target)).start()
        try {
          process.outputStream.write("GET / HTTP/1.1\r\nAuthorization: Basic must-not-leak\r\n\r\n".toByteArray())
          process.outputStream.flush()
          assertEquals("LAGOON_SOCKET_UNVERIFIED_V1", line(process.inputStream))
          val first = server.requests.poll(5, TimeUnit.SECONDS)
          assertNotNull(first)
          assertFalse(first!!.contains("Authorization:", ignoreCase = true))
          assertEquals("Only the anonymous activation may reach the wrong process", null,
            server.requests.poll(1, TimeUnit.SECONDS))
        } finally { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
      }
    } finally { unrelated.destroyForcibly(); unrelated.waitFor(5, TimeUnit.SECONDS) }
  }

  private fun registration(port: Int, pid: Int): SshVerifiedTunnel.Target {
    val scratch = File(System.getenv("BUILD_SCRATCH_ROOT") ?: File(System.getProperty("user.home"), ".build").path)
    val root = File(scratch, "Lagoon/ssh-proof-fixtures").apply { mkdirs() }
    val directory = Files.createTempDirectory(root.toPath(), "registration-")
    val path = directory.resolve("state with ' quote.json")
    Files.write(path, JSONObject().put("pid", pid).put("url", "http://127.0.0.1:$port/")
      .put("version", "2.2.6").put("id", "proof-fixture").toString().toByteArray())
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    return SshVerifiedTunnel.Target("127.0.0.1", port, pid, registrationPath = path.toString(),
      registrationVersion = "2.2.6", registrationId = "proof-fixture")
  }

  private fun assumeSupportedHost() {
    val os = System.getProperty("os.name").lowercase()
    assumeTrue(os.contains("linux") || os.contains("mac"))
    if (os.contains("mac")) assumeTrue(File("/usr/sbin/lsof").isFile)
  }

  private fun line(input: InputStream): String {
    val reader = Executors.newSingleThreadExecutor { r -> Thread(r, "lagoon-proof-test-read").apply { isDaemon = true } }
    try {
      return reader.submit<String> {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 512) {
          val value = input.read()
          if (value < 0 || value == 10) break
          if (value != 13) bytes.write(value)
        }
        bytes.toString(Charsets.US_ASCII.name())
      }.get(12, TimeUnit.SECONDS)
    } finally { reader.shutdownNow() }
  }

  private class LocalServer : Closeable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = listener.localPort
    val requests = LinkedBlockingQueue<String>()
    private val worker = Thread({
      try {
        listener.accept().use { socket ->
          socket.soTimeout = 8_000
          val input = socket.getInputStream()
          val output = socket.getOutputStream()
          while (true) {
            val header = ByteArrayOutputStream()
            while (header.size() < 16 * 1024) {
              val value = input.read()
              if (value < 0) return@Thread
              header.write(value)
              if (header.size() >= 4 && header.toByteArray().takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
            }
            requests.put(header.toString(Charsets.US_ASCII.name()))
            output.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n".toByteArray())
            output.flush()
          }
        }
      } catch (_: Exception) { }
    }, "lagoon-proof-test-server").apply { isDaemon = true; start() }
    override fun close() { listener.close(); worker.join(10_000) }
  }
}
