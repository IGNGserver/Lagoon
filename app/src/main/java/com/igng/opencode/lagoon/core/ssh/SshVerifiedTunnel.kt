package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.OpenCodeSshRegistration
import com.igng.opencode.lagoon.core.RemoteDshEndpoint
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshShell
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * One SSH exec channel owns exactly one remote loopback socket. The remote helper proves that the
 * registered process (or a managed DSH descendant) owns that *same socket* before READY. Until
 * READY, no bytes from the local client, including credentials, are written to the channel.
 */
internal object SshVerifiedTunnel {
  private const val READY = "LAGOON_SOCKET_READY_V1"
  private const val UNAVAILABLE = "LAGOON_SOCKET_UNAVAILABLE_V1"
  private const val UNSUPPORTED = "LAGOON_SOCKET_UNSUPPORTED_V1"
  private const val DENIED = "LAGOON_SOCKET_UNVERIFIED_V1"

  internal data class Target(
    val host: String,
    val port: Int,
    val pid: Int,
    val registrationPath: String? = null,
    val registrationVersion: String? = null,
    val registrationId: String? = null,
    val dshStateDirectory: String? = null
  ) {
    init {
      require(host in setOf("127.0.0.1", "localhost", "::1"))
      require(port in 1..65535 && pid > 0)
      require((registrationPath != null) != (dshStateDirectory != null))
      require(registrationPath == null || registrationPath.startsWith('/') && registrationPath.length <= 4096 && registrationPath.none(Char::isISOControl))
      require(dshStateDirectory == null || dshStateDirectory in setOf(".lagoon-ssh-dsh", ".dsh-app-remote"))
    }
  }

  fun openCode(registration: OpenCodeSshRegistration): Target = Target(
    registration.host, registration.port, registration.pid, registrationPath = registration.registrationPath,
    registrationVersion = registration.version, registrationId = registration.generationId
  )

  fun dsh(endpoint: RemoteDshEndpoint): Target = Target(
    "127.0.0.1", endpoint.port,
    endpoint.pid ?: throw SshFailure(SshFailureKind.OTHER, "无法确认 DSH 进程身份，请重新连接设备", retryable = false),
    dshStateDirectory = endpoint.ownershipStateDirectory
      ?: throw SshFailure(SshFailureKind.OTHER, "无法确认 DSH 状态目录，请重新连接设备", retryable = false)
  )

  /** How much of the remote socket ownership could be proven on this host. */
  internal enum class Proof {
    /** The remote helper proved that the recorded process (or its child) owns the socket. */
    VERIFIED,
    /** No python3/lsof on the remote: only the managed pid/start record could be checked. */
    DEGRADED
  }

  /**
   * Proves the listener once per SSH generation. Repeating the proof for every socket spawned a
   * python3 process, a full `/proc` snapshot and an extra HTTP request per HTTP connection (DSH
   * fans out four reads at a time), which is what pushed connections past the read timeout and
   * consumed sshd sessions. Later sockets reuse [openDirect] on the same proven generation.
   *
   * A remote without python3 (or macOS without lsof) degrades to the record-level check the
   * bootstrap already performed (managed pid + start stamp + boot id); the caller reports it.
   */
  fun verify(transport: SshTransport, target: Target): Proof {
    val channel = transport.openExecChannel(command(target))
    discardStderr(channel)
    val reply = readProof(channel)
    runCatching { channel.close() }
    return when (reply) {
      READY -> Proof.VERIFIED
      UNSUPPORTED -> Proof.DEGRADED
      DENIED -> throw SshFailure(SshFailureKind.OTHER, "无法确认远端服务进程身份，请检查注册文件和进程权限", retryable = false)
      UNAVAILABLE -> throw SshFailure(SshFailureKind.UNRESPONSIVE, "远端服务暂时无法响应安全检查")
      else -> throw SshFailure(SshFailureKind.OTHER, "远端安全检查返回了无效结果", retryable = false)
    }
  }

  /** A plain channel on an already-proven generation: no helper process, no extra request. */
  fun openDirect(transport: SshTransport, target: Target, input: InputStream, output: OutputStream): TunnelConnection =
    transport.openTunnel("127.0.0.1", target.port, input, output)

  fun open(transport: SshTransport, target: Target, input: InputStream, output: OutputStream): TunnelConnection {
    val channel = verifiedChannel(transport, target)
    return ExecTunnel(channel, input, output).also { it.start() }
  }

  internal fun command(target: Target): String {
    val params = JSONObject().put("host", if (target.host == "localhost") "127.0.0.1" else target.host)
      .put("port", target.port).put("pid", target.pid)
    if (target.registrationPath != null) {
      params.put("kind", "opencode").put("path", target.registrationPath)
      target.registrationVersion?.let { params.put("version", it) }
      target.registrationId?.let { params.put("id", it) }
    } else params.put("kind", "dsh").put("state", target.dshStateDirectory)
    // No token, password, cookie, or request bytes are ever passed in a process argument.
    val encoded = Base64.getEncoder().encodeToString(params.toString().toByteArray(Charsets.UTF_8))
    return listOf("python3", "-u", "-c", SshShell.quote(HELPER), SshShell.quote(encoded)).joinToString(" ")
  }

  /** Never retain stderr: login shells or the remote runtime may print paths or credentials. */
  private fun discardStderr(channel: SshExecChannel) {
    Thread({ runCatching { val b = ByteArray(4096); while (channel.stderr.read(b) >= 0) { } } },
      "lagoon-ssh-proof-stderr").apply { isDaemon = true; start() }
  }

  private fun readProof(channel: SshExecChannel): String {
    val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "lagoon-ssh-proof-ready").apply { isDaemon = true } }
    return try {
      executor.submit<String> { readHandshake(channel.stdout) }.get(12, TimeUnit.SECONDS)
    } catch (_: Exception) {
      // A login shell banner or a timeout is not proof of a foreign socket; report it as unavailable.
      UNAVAILABLE
    } finally { executor.shutdownNow() }
  }

  private fun verifiedChannel(transport: SshTransport, target: Target): SshExecChannel {
    val channel = transport.openExecChannel(command(target))
    discardStderr(channel)
    when (readProof(channel)) {
      READY -> return channel
      UNSUPPORTED -> {
        runCatching { channel.close() }
        throw SshFailure(SshFailureKind.OTHER, "开发机缺少安全验证所需的 Python 3、lsof 或进程信息", retryable = false)
      }
      DENIED -> {
        runCatching { channel.close() }
        throw SshFailure(SshFailureKind.OTHER, "无法确认远端服务进程身份，请检查注册文件和进程权限", retryable = false)
      }
      UNAVAILABLE -> {
        runCatching { channel.close() }
        throw SshFailure(SshFailureKind.UNRESPONSIVE, "远端服务暂时无法响应安全检查")
      }
      else -> {
        runCatching { channel.close() }
        throw SshFailure(SshFailureKind.OTHER, "远端安全检查返回了无效结果", retryable = false)
      }
    }
  }

  private fun readHandshake(input: InputStream): String {
    val buffer = ByteArrayOutputStream()
    while (buffer.size() < 128) {
      val next = input.read()
      if (next < 0) throw EOFException("Remote proof ended")
      if (next == 10) return buffer.toString(Charsets.US_ASCII.name())
      if (next !in 32..126) throw EOFException("Invalid remote proof")
      buffer.write(next)
    }
    throw EOFException("Remote proof exceeds limit")
  }

  private class ExecTunnel(
    private val channel: SshExecChannel,
    private val input: InputStream,
    private val output: OutputStream
  ) : TunnelConnection {
    private val lock = Any()
    private var closed = false
    private val listeners = mutableListOf<() -> Unit>()
    override val isOpen: Boolean get() = synchronized(lock) { !closed && channel.isOpen }

    fun start() {
      pump("lagoon-ssh-proof-send", input, channel.stdin)
      pump("lagoon-ssh-proof-receive", channel.stdout, output)
    }

    private fun pump(name: String, source: InputStream, sink: OutputStream) {
      Thread({
        try {
          val buffer = ByteArray(16 * 1024)
          while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            if (count > 0) { sink.write(buffer, 0, count); sink.flush() }
          }
        } catch (_: Exception) { } finally { close() }
      }, name).apply { isDaemon = true; start() }
    }

    override fun onClosed(listener: () -> Unit) {
      val now = synchronized(lock) { if (closed) true else { listeners += listener; false } }
      if (now) runCatching { listener() }
    }

    override fun close() {
      val callbacks = synchronized(lock) {
        if (closed) return
        closed = true
        listeners.toList().also { listeners.clear() }
      }
      runCatching { channel.close() }
      callbacks.forEach { runCatching { it() } }
    }
  }

  /**
   * Remote program. It performs an anonymous bounded activation on the final socket, then proves
   * the server end is owned by the registered process using OS-provided socket/process metadata.
   * The actual HTTP or WebSocket handshake follows on that same socket only after READY.
   */
  internal val HELPER = """
import base64, http.client, json, os, re, signal, socket, stat, subprocess, sys, threading

READY='LAGOON_SOCKET_READY_V1'
UNAVAILABLE='LAGOON_SOCKET_UNAVAILABLE_V1'
UNSUPPORTED='LAGOON_SOCKET_UNSUPPORTED_V1'
DENIED='LAGOON_SOCKET_UNVERIFIED_V1'
class Denied(Exception): pass
class Unsupported(Exception): pass
def require(ok):
    if not ok: raise Denied()
def bounded_file(path, maximum=16384):
    require(hasattr(os, 'O_NOFOLLOW'))
    try: fd=os.open(path, os.O_RDONLY | os.O_NOFOLLOW | getattr(os, 'O_NONBLOCK', 0))
    except OSError: raise Denied()
    try:
        info=os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and not info.st_mode & 0o002)
        with os.fdopen(fd, 'rb') as f:
            fd=-1
            data=f.read(maximum+1)
        require(len(data)<=maximum)
        return data
    finally:
        if fd>=0: os.close(fd)
def process_snapshot():
    result={}
    uid=os.getuid()
    if sys.platform.startswith('linux'):
        entries=os.listdir('/proc')
        require(len(entries)<=65536)
        for name in entries:
            if not name.isdigit(): continue
            try:
                path='/proc/'+name
                if os.stat(path).st_uid != uid: continue
                with open(path+'/stat','r',encoding='ascii') as f: value=f.read(8192)
                fields=value[value.rfind(')')+2:].split()
                if len(fields)<20: continue
                result[int(name)]=(int(fields[1]), fields[19])
            except (OSError, ValueError): continue
    elif sys.platform == 'darwin':
        env=dict(os.environ); env['LC_ALL']='C'
        try:
            raw=subprocess.check_output(['/bin/ps','-axo','pid=,ppid=,ruid=,lstart='],stderr=subprocess.DEVNULL,timeout=4,env=env)
        except (OSError, subprocess.SubprocessError): raise Unsupported()
        require(len(raw)<=4*1024*1024)
        for line in raw.decode('ascii','strict').splitlines():
            fields=line.strip().split(None,3)
            if len(fields)!=4: continue
            try:
                pid,ppid,owner=(int(fields[0]),int(fields[1]),int(fields[2]))
                if owner==uid: result[pid]=(ppid,' '.join(fields[3].split()))
            except ValueError: continue
    else: raise Unsupported()
    return result
def chain(pid, root, snapshot, descendants):
    path=[]; seen=set()
    while pid not in seen and len(path)<256:
        seen.add(pid)
        item=snapshot.get(pid)
        if item is None: return None
        path.append((pid,item))
        if pid==root: return path
        if not descendants: return None
        pid=item[0]
    return None
def eligible(root, snapshot, descendants):
    if not descendants: return [root] if root in snapshot else []
    return [pid for pid in snapshot if chain(pid, root, snapshot, True)]
def verify_linux(pids, host, server_port, client_port):
    # Linux can report an IPv4 connection accepted by a dual-stack listener as an
    # IPv4-mapped IPv6 socket. Both addresses and ports must still match exactly.
    tables=([('/proc/net/tcp6','00000000000000000000000001000000')] if host=='::1' else
        [('/proc/net/tcp','0100007F'),('/proc/net/tcp6','0000000000000000FFFF00000100007F')])
    inodes={}
    for pid in pids:
        try:
            fds=os.listdir('/proc/'+str(pid)+'/fd')
            require(len(fds)<=4096)
            for fd in fds:
                try:
                    match=re.fullmatch(r'socket:\[(\d+)\]',os.readlink('/proc/'+str(pid)+'/fd/'+fd))
                    if match: inodes.setdefault(match.group(1),set()).add(pid)
                except OSError: continue
        except OSError: continue
    for table,endpoint in tables:
        try:
            with open(table,'r',encoding='ascii') as f: rows=f.read(4*1024*1024+1)
        except FileNotFoundError: continue
        require(len(rows)<=4*1024*1024)
        for line in rows.splitlines()[1:]:
            fields=line.split()
            if len(fields)<10 or fields[3]!='01': continue
            local=fields[1].split(':'); remote=fields[2].split(':')
            if len(local)!=2 or len(remote)!=2: continue
            try:
                if (local[0]==endpoint and remote[0]==endpoint and int(local[1],16)==server_port and
                    int(remote[1],16)==client_port and fields[9] in inodes): return next(iter(inodes[fields[9]]))
            except ValueError: continue
    raise Denied()
def verify_darwin(pids, host, server_port, client_port):
    require(len(pids)<=256)
    if not pids: raise Denied()
    addr='[::1]' if host=='::1' else '127.0.0.1'
    expected=('n'+addr+':'+str(server_port)+'->'+addr+':'+str(client_port)).encode('ascii')
    executable='/usr/sbin/lsof'
    if not os.path.isfile(executable): raise Unsupported()
    env=dict(os.environ); env['LC_ALL']='C'
    try:
        proc=subprocess.run([executable,'-nP','-a','-p',','.join(map(str,pids)), '-iTCP',
            '-sTCP:ESTABLISHED','-F0pfnT'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=4,env=env)
    except (OSError, subprocess.SubprocessError): raise Unsupported()
    # lsof exits 1 if any requested process has disappeared or has no matching socket, even
    # when other PIDs have valid output. Accept that status only after parsing an exact
    # established four-tuple owned by an eligible PID below; empty/partial output still denies.
    require(proc.returncode in (0,1) and len(proc.stdout)<=4*1024*1024)
    current=None
    for line in proc.stdout.split(b'\n'):
        fields=[f for f in line.split(b'\0') if f]
        for value in fields:
            if value.startswith(b'p') and value[1:].isdigit(): current=int(value[1:])
        if current in pids and expected in fields and b'TST=ESTABLISHED' in fields and any(f.startswith(b'f') and f[1:].isdigit() for f in fields): return current
    raise Denied()
def target_state(data):
    kind=data['kind']; root=int(data['pid']); port=int(data['port']); host=data['host']
    require(kind in ('opencode','dsh') and root>0 and 1<=port<=65535 and host in ('127.0.0.1','::1'))
    if kind=='opencode':
        path=data['path']; require(os.path.isabs(path) and len(path)<=4096)
        def unique(pairs):
            result={}
            for key,value in pairs:
                require(key not in result); result[key]=value
            return result
        value=json.loads(bounded_file(path).decode('utf-8'),object_pairs_hook=unique)
        require(isinstance(value,dict) and type(value.get('pid')) is int and value.get('pid')==root)
        require(all(key in ('id','version','url','pid','password') for key in value))
        authority='[::1]' if host=='::1' else '127.0.0.1'
        urls={'http://'+authority+':'+str(port),'http://'+authority+':'+str(port)+'/'}
        if host=='127.0.0.1': urls |= {'http://localhost:'+str(port),'http://localhost:'+str(port)+'/'}
        require(value.get('url') in urls)
        for key in ('id','version'):
            if key in data: require(value.get(key)==data[key])
        return root, False, None
    dirname=data['state']; require(dirname in ('.lagoon-ssh-dsh','.dsh-app-remote'))
    home=os.environ.get('HOME',''); require(os.path.isabs(home))
    folder=os.path.join(home,dirname)
    info=os.lstat(folder)
    require(stat.S_ISDIR(info.st_mode) and info.st_uid==os.getuid() and not info.st_mode & 0o002)
    require(bounded_file(os.path.join(folder,'web.pid'),128).decode('ascii').strip()==str(root))
    start=' '.join(bounded_file(os.path.join(folder,'web.st'),256).decode('ascii').split())
    require(bool(start))
    bootpath=os.path.join(folder,'web.boot')
    if os.path.exists(bootpath):
        boot=bounded_file(bootpath,128).decode('ascii').strip()
        if sys.platform.startswith('linux'):
            with open('/proc/sys/kernel/random/boot_id','r',encoding='ascii') as f: require(f.read(128).strip()==boot)
    return root, True, start
def deadline(_signum,_frame): raise TimeoutError()
channel=None
announced=False
try:
    signal.signal(signal.SIGALRM,deadline); signal.setitimer(signal.ITIMER_REAL,8)
    data=json.loads(base64.b64decode(sys.argv[1],validate=True).decode('utf-8'))
    root,descendants,start=target_state(data)
    host=data['host']; port=int(data['port'])
    before=process_snapshot(); require(root in before)
    if start is not None: require(before[root][1]==start)
    family=socket.AF_INET6 if host=='::1' else socket.AF_INET
    channel=socket.socket(family,socket.SOCK_STREAM); channel.settimeout(5); channel.connect((host,port))
    client_port=channel.getsockname()[1]
    authority='[::1]' if host=='::1' else host
    path='/api/info' if data['kind']=='opencode' else '/'
    channel.sendall(('GET '+path+' HTTP/1.1\r\nHost: '+authority+':'+str(port)+'\r\nConnection: keep-alive\r\nAccept: application/json\r\n\r\n').encode('ascii'))
    response=http.client.HTTPResponse(channel); response.begin()
    require(response.status in (200,301,302,303,307,308,401,403,404) and not response.will_close)
    require(response.length is not None or response.chunked)
    require(len(response.read(1024*1024+1))<=1024*1024)
    pids=eligible(root,before,descendants); require(len(pids)<=256)
    owner=verify_linux(pids,host,port,client_port) if sys.platform.startswith('linux') else verify_darwin(pids,host,port,client_port)
    after=process_snapshot()
    require(chain(owner,root,before,descendants)==chain(owner,root,after,descendants) and chain(owner,root,after,descendants) is not None)
    require(target_state(data)==(root,descendants,start))
    channel.settimeout(None); signal.setitimer(signal.ITIMER_REAL,0)
    sys.stdout.write(READY+'\n'); sys.stdout.flush(); announced=True
    def send_input():
        try:
            while True:
                block=os.read(0,16384)
                if not block: break
                channel.sendall(block)
        except OSError: pass
        finally:
            try: channel.shutdown(socket.SHUT_RDWR)
            except OSError: pass
    threading.Thread(target=send_input,daemon=True).start()
    while True:
        block=channel.recv(16384)
        if not block: break
        view=memoryview(block)
        while view:
            written=os.write(1,view)
            view=view[written:]
except Unsupported:
    if not announced: print(UNSUPPORTED,flush=True)
except Denied:
    if not announced: print(DENIED,flush=True)
except (OSError, TimeoutError, EOFError, subprocess.SubprocessError):
    if not announced: print(UNAVAILABLE,flush=True)
except Exception:
    if not announced: print(DENIED,flush=True)
finally:
    if channel is not None:
        try: channel.close()
        except OSError: pass
""".trimIndent()
}
