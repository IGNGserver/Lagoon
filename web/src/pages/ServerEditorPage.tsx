import { useEffect, useState } from "react";
import { del, enc, errorText, post, put } from "@/api/http";
import type { DshAuthMode, ServerKind, ServerProfile } from "@/api/types";
import { Button, ConfirmDialog, ListGroup, ListItem, PageHeader, SegmentedControl, Spinner, Switch, TextField } from "@/components/ui";
import { back, navigate } from "@/lib/router";
import { useStore } from "@/store";

interface Parsed { kind?: ServerKind; url?: string; secret?: string; dshAuth?: DshAuthMode; allowCleartext?: boolean; pair?: boolean }

const isPairLink = (value: string) => /[?&]auth_token=|\/auth\/connect\//.test(value);

/** Add or edit one server. Saving tests the connection on the hub first; a failed test saves nothing. */
export function ServerEditorPage({ id }: { id: string | null }) {
  const existing = useStore((state) => (id ? state.servers[id]?.profile : undefined));
  const hasSecret = useStore((state) => (id ? state.servers[id]?.hasSecret ?? false : false));
  const loaded = useStore((state) => state.loaded);
  const toast = useStore((state) => state.toast);
  const [kind, setKind] = useState<ServerKind>(existing?.kind ?? "opencode");
  const [name, setName] = useState(existing?.name ?? "");
  const [url, setUrl] = useState(existing?.url ?? "");
  const [username, setUsername] = useState(existing?.kind === "opencode" ? existing.username : "opencode");
  const [secret, setSecret] = useState("");
  const [dshAuth, setDshAuth] = useState<DshAuthMode>(existing?.dshAuth ?? "token");
  const [allowCleartext, setAllowCleartext] = useState(existing?.allowCleartext ?? false);
  const [autoConnect, setAutoConnect] = useState(existing?.autoConnect ?? true);
  const [notifications, setNotifications] = useState(existing?.notifications ?? true);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [removing, setRemoving] = useState(false);

  // The profile arrives with the hub state when the page is opened directly.
  useEffect(() => {
    if (!existing) return;
    setKind(existing.kind); setName(existing.name); setUrl(existing.url); setUsername(existing.kind === "opencode" ? existing.username : "opencode");
    setDshAuth(existing.dshAuth); setAllowCleartext(existing.allowCleartext); setAutoConnect(existing.autoConnect); setNotifications(existing.notifications);
  }, [existing?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const dsh = kind === "dsh";
  const pair = !dsh && isPairLink(url.trim());
  const http = url.trim().toLowerCase().startsWith("http://");
  const authChanged = dsh && existing != null && existing.dshAuth !== dshAuth;

  async function recognize(text: string) {
    try {
      const parsed = await post<Parsed>("/servers/parse", { text });
      if (parsed.kind === "dsh") {
        setKind("dsh"); setDshAuth("token");
        if (parsed.secret) setSecret(parsed.secret);
        if (!name) setName("我的 DeepSeek Harness");
      }
      if (parsed.url) setUrl(parsed.url);
      if (parsed.allowCleartext) setAllowCleartext(true);
      if (parsed.pair && !name) setName("我的服务器");
    } catch {
      // Keep what was pasted.
    }
  }

  async function save() {
    if (authChanged && !secret.trim()) { setResult(`切换认证方式后请填写新的${dshAuth === "secret" ? "签名密钥" : "启动令牌"}`); return; }
    setBusy(true);
    setResult(null);
    const profile: Partial<ServerProfile> = {
      name: name.trim() || (dsh ? "DeepSeek Harness" : "OpenCode"), url: url.trim().replace(/\/+$/, ""), username: dsh ? "dsh" : username.trim() || "opencode",
      kind, dshAuth: dsh ? dshAuth : "token", allowCleartext: http && allowCleartext, autoConnect, notifications,
    };
    const body = { profile, ...(secret || !existing ? { secret: dsh ? secret.trim() : secret } : {}) };
    try {
      const saved = id ? await put<{ profile: ServerProfile; version: string }>(`/servers/${enc(id)}`, body)
        : await post<{ profile: ServerProfile; version: string }>("/servers", body);
      toast(`已连接 ${dsh ? "DeepSeek Harness" : "OpenCode"}${saved.version && saved.version !== "DeepSeek Harness" ? ` ${saved.version}` : ""}`);
      back("/");
    } catch (error) {
      setResult(errorText(error, "连接失败"));
    } finally {
      setBusy(false);
    }
  }

  if (id && !existing) {
    return (
      <div className="flex h-full flex-col bg-bg">
        <PageHeader title="编辑服务器" onBack={() => back()} />
        {loaded ? <p className="p-8 text-center text-text-2">服务器不存在或已移除</p> : <div className="flex justify-center p-10"><Spinner size={22} /></div>}
      </div>
    );
  }

  const canSave = !busy && name.trim().length >= 0 && (url.trim().toLowerCase().startsWith("https://") || (http && allowCleartext));

  return (
    <div className="flex h-full min-h-0 flex-col bg-bg">
      <PageHeader title={id ? "编辑服务器" : "添加服务器"} onBack={() => back()} className="bg-bg" />
      <div className="scroll-area min-h-0 flex-1 pb-[calc(24px+var(--safe-bottom))]">
        <div className="mx-auto max-w-[640px]">
          <p className="mx-5 mb-4 rounded-2xl bg-primary-subtle px-4 py-3 text-[13.5px] leading-relaxed text-primary">
            {dsh ? "直接粘贴 dsh web 打印的完整启动链接即可配对；配对后 Cookie 保存在中枢，dsh 重启也不用重新配对。想完全不看电脑，可改用「签名密钥」。"
              : "推荐在电脑端运行 opencode pair，把配对链接粘贴到下方，即可免密码配置认证。"}
          </p>
          <div className="mx-3 mb-5"><SegmentedControl value={kind} onChange={(value) => { setKind(value); if (value === "dsh") setUsername("opencode"); }}
            options={[{ value: "opencode", label: "OpenCode" }, { value: "dsh", label: "DeepSeek Harness" }]} /></div>
          <div className="mx-4 space-y-4">
            <TextField label="名称" value={name} onChange={(e) => setName(e.target.value)} placeholder={dsh ? "我的 DeepSeek Harness" : "我的服务器"} />
            <TextField label={pair ? "配对链接" : "地址"} value={url} inputMode="url" autoCapitalize="off" autoCorrect="off" spellCheck={false}
              onChange={(e) => setUrl(e.target.value)} onPaste={(e) => { const text = e.clipboardData.getData("text"); if (text) { e.preventDefault(); setUrl(text.trim()); void recognize(text.trim()); } }}
              placeholder={dsh ? "http://192.168.1.100:3080" : "https://oc.example.com 或 http://192.168.1.100:4096"}
              hint={dsh ? "dsh 默认只监听本机，请用 dsh web --host <电脑的局域网 IP> --no-open 启动。中枢在容器里，localhost 指的是容器自己，同一台机器请填 host.docker.internal。"
                : "中枢替浏览器连接开发机：填中枢能访问到的地址。同一台机器上的服务请填 host.docker.internal。"} />
            {dsh && (
              <SegmentedControl value={dshAuth} onChange={setDshAuth} options={[{ value: "token", label: "启动令牌" }, { value: "secret", label: "签名密钥" }]} />
            )}
            {!pair && !dsh && <TextField label="用户名" value={username} onChange={(e) => setUsername(e.target.value)} autoCapitalize="off" />}
            {!pair && (
              <TextField label={dsh ? (dshAuth === "secret" ? "签名密钥" : "启动令牌") : "密码"} type="password" value={secret} onChange={(e) => setSecret(e.target.value)}
                autoComplete="off" placeholder={existing && hasSecret ? "留空保持不变" : undefined}
                hint={dsh ? (dshAuth === "secret"
                  ? "~/.dsh/.credentials.yaml 里 client-connection/browser-session 记录的 secret（43 个字符），相当于这台 dsh 的长期密码。"
                  : "启动链接里 token= 后面的字符；只用来换 Cookie，默认 30 天有效。") : "OpenCode Server 的 Basic Auth 密码。"} />
            )}
          </div>
          <ListGroup className="mt-6">
            {http && <ListItem title="允许明文连接" summary="HTTP 不加密，凭据与消息在网络中可见；只在可信局域网中开启" trailing={<Switch checked={allowCleartext} onChange={setAllowCleartext} label="允许明文连接" />} />}
            <ListItem title="自动连接" summary="中枢常驻监控这台服务器；关闭后只在网页打开它时连接" trailing={<Switch checked={autoConnect} onChange={setAutoConnect} label="自动连接" />} />
            <ListItem title="推送通知" summary="任务完成、失败或等待处理时推送" trailing={<Switch checked={notifications} onChange={setNotifications} label="推送通知" />} />
          </ListGroup>
          {result && <p className="mx-5 mb-3 text-[14px] text-error">{result}</p>}
          <div className="mx-4 space-y-3">
            <Button size="lg" block disabled={!canSave} onClick={save}>{busy ? <><Spinner className="text-on-primary" />正在测试…</> : "保存并连接"}</Button>
            {id && <Button size="lg" variant="danger" block onClick={() => setDeleting(true)}>从中枢移除</Button>}
          </div>
        </div>
      </div>
      <ConfirmDialog open={deleting} onOpenChange={setDeleting} title={`移除 ${existing?.name ?? "服务器"}？`} danger confirm="确认移除" busy={removing}
        message="只删除中枢里的连接记录和凭据，不会影响远程服务器上的数据。"
        onConfirm={async () => {
          setRemoving(true);
          try { await del(`/servers/${enc(id!)}`); navigate("/", { replace: true }); }
          catch (error) { toast(errorText(error), "error"); setRemoving(false); }
        }} />
    </div>
  );
}
