import { Bell, Download, KeyRound, LogOut, Monitor, Plus, Server, ShieldCheck, Smartphone, TabletSmartphone } from "lucide-react";
import { useEffect, useState } from "react";
import { del, enc, errorText, get, post, put } from "@/api/http";
import { live } from "@/api/live";
import type { AuthStatus, ClientKey, ModelInfo, ServerView } from "@/api/types";
import { StatusDot } from "@/components/StatusGlyph";
import { Button, ConfirmDialog, ListGroup, ListItem, PageHeader, SegmentedControl, Sheet, Spinner, Switch, TextField } from "@/components/ui";
import { importLink, keySummary } from "@/lib/clientAccess";
import { serverEditPath, back, navigate } from "@/lib/router";
import { canInstall, currentSubscription, disablePush, enablePush, install, isIos, isStandalone, onInstallChange, pushSupport, testPush } from "@/lib/pwa";
import { useShallow } from "zustand/react/shallow";
import { servers as serverList, useStore, type ThemeMode } from "@/store";

export function SettingsPage() {
  const all = useStore(useShallow(serverList));
  const auth = useStore((state) => state.auth);
  const policy = useStore((state) => state.policy);
  const refreshMode = useStore((state) => state.refreshMode);
  const theme = useStore((state) => state.theme);
  const setTheme = useStore((state) => state.setTheme);
  const toast = useStore((state) => state.toast);
  const [models, setModels] = useState<ServerView | null>(null);
  const [password, setPassword] = useState(false);
  const [revoke, setRevoke] = useState(false);

  const savePrefs = async (body: object) => {
    try {
      await put("/prefs", body);
      useStore.setState(body as never);
    } catch (error) {
      toast(errorText(error), "error");
    }
  };

  return (
    <div className="flex h-full min-h-0 flex-col bg-bg">
      <PageHeader title="设置" onBack={() => back()} className="bg-bg" />
      <div className="scroll-area min-h-0 flex-1 pb-[calc(24px+var(--safe-bottom))]">
        <div className="mx-auto max-w-[680px]">
          <ListGroup title="服务器">
            {all.map((server) => (
              <ListItem key={server.serverId} icon={Server} title={<span className="flex items-center gap-2"><StatusDot tone={server.connected && server.streamConnected ? "ok" : server.connected || server.loading ? "busy" : "off"} />{server.profile.name}</span>}
                summary={`${server.profile.kind === "dsh" ? "DeepSeek Harness" : "OpenCode"}${server.version && server.version !== "DeepSeek Harness" ? ` ${server.version}` : ""} · ${server.profile.url}`}
                chevron onClick={() => navigate(serverEditPath(server.serverId))} />
            ))}
            <ListItem icon={Plus} title="添加服务器" summary="OpenCode 地址 / pair 链接，或 DeepSeek Harness 启动链接" chevron onClick={() => navigate(serverEditPath(null))} />
          </ListGroup>

          <ClientAccessSettings />
          <PushSettings />
          <InstallSettings />

          <ListGroup title="权限请求" footer="自动允许只回复「允许一次」，不会改动服务器的权限规则。">
            <ListItem title="隐藏权限请求" summary="不在对话中显示，也不推送" trailing={<Switch checked={policy.hide} label="隐藏权限请求"
              onChange={(hide) => savePrefs({ policy: { ...policy, hide } })} />} />
            <ListItem title="自动允许" summary="中枢收到权限请求时自动允许一次" trailing={<Switch checked={policy.autoApprove} label="自动允许"
              onChange={(autoApprove) => savePrefs({ policy: { ...policy, autoApprove } })} />} />
          </ListGroup>

          <ListGroup title="刷新频率" footer="实时更新以事件流为主；对账只是兜底，「更快」在有任务时 10 秒、空闲时 30 秒对账一次。">
            <div className="p-3"><SegmentedControl value={refreshMode} onChange={(value) => savePrefs({ refreshMode: value })}
              options={[{ value: "STANDARD", label: "标准" }, { value: "FAST", label: "更快" }]} /></div>
          </ListGroup>

          {all.length > 0 && (
            <ListGroup title="模型" footer="默认显示服务器提供的全部模型；勾选后只显示选中的模型。">
              {all.map((server) => <ListItem key={server.serverId} title={server.profile.name} summary="选择模型选择器中显示的模型" chevron disabled={!server.connected} onClick={() => setModels(server)} />)}
            </ListGroup>
          )}

          <ListGroup title="外观">
            <div className="p-3"><SegmentedControl<ThemeMode> value={theme} onChange={setTheme}
              options={[{ value: "system", label: "跟随系统" }, { value: "light", label: "浅色" }, { value: "dark", label: "深色" }]} /></div>
          </ListGroup>

          <TlsSettings auth={auth} />

          <ListGroup title="安全" footer={auth?.passwordFromEnv ? "访问密码由环境变量 LAGOON_PASSWORD 管理，修改需要更新部署配置。" : undefined}>
            {!auth?.passwordFromEnv && <ListItem icon={KeyRound} title="修改访问密码" chevron onClick={() => setPassword(true)} />}
            <ListItem icon={ShieldCheck} title="退出所有设备" summary="包括本设备，所有浏览器都需要重新登录" onClick={() => setRevoke(true)} />
            <ListItem icon={LogOut} title="退出登录" danger onClick={async () => {
              await post("/auth/logout").catch(() => undefined);
              live.stop();
              useStore.getState().setAuth({ ...auth!, authenticated: false });
            }} />
          </ListGroup>

          <ListGroup title="关于">
            <ListItem title="网页版本" trailing={<span className="text-[14px] text-text-2">{__APP_VERSION__}</span>} />
            <ListItem title="中枢版本" trailing={<span className="text-[14px] text-text-2">{auth?.version}</span>} />
            <ListItem title="源代码与发布说明" chevron onClick={() => window.open("https://github.com/IGNGserver/Lagoon", "_blank", "noopener")} />
          </ListGroup>
        </div>
      </div>
      {models && <ModelVisibilitySheet server={models} onClose={() => setModels(null)} />}
      {password && <PasswordSheet onClose={() => setPassword(false)} />}
      <ConfirmDialog open={revoke} onOpenChange={setRevoke} title="退出所有设备？" confirm="全部退出" danger
        message="所有已登录的浏览器（包括本设备）都需要重新输入访问密码。"
        onConfirm={async () => {
          await post("/auth/revoke").catch(() => undefined);
          live.stop();
          useStore.getState().setAuth({ ...auth!, authenticated: false });
        }} />
    </div>
  );
}

/** Access keys that let the Android app import this hub's servers with their credentials. */
function ClientAccessSettings() {
  const toast = useStore((state) => state.toast);
  const [keys, setKeys] = useState<ClientKey[] | null>(null);
  const [creating, setCreating] = useState(false);
  const [revoking, setRevoking] = useState<ClientKey | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () => get<{ keys: ClientKey[] }>("/client-keys").then((it) => setKeys(it.keys)).catch(() => setKeys([]));
  useEffect(() => { void load(); }, []);

  return (
    <>
      <ListGroup title="客户端接入" footer="在 Lagoon Android 客户端「设置 → 从中枢导入」粘贴导入链接，即可一次导入这里的全部服务器（含凭据）。导入内容用密钥加密，HTTP 访问也不会在网络上暴露凭据；密钥与访问密码一样敏感，不用了请吊销。">
        {keys == null && <ListItem title="正在读取…" trailing={<Spinner />} />}
        {keys?.map((key) => (
          <ListItem key={key.id} icon={TabletSmartphone} title={key.label} summary={keySummary(key)} onClick={() => setRevoking(key)} />
        ))}
        <ListItem icon={Plus} title="新建接入密钥" summary="为一台设备生成导入链接" chevron onClick={() => setCreating(true)} />
      </ListGroup>
      {creating && <ClientKeySheet onClose={() => { setCreating(false); void load(); }} />}
      <ConfirmDialog open={revoking != null} onOpenChange={(open) => !open && setRevoking(null)} title={`吊销「${revoking?.label ?? ""}」？`}
        confirm="吊销" danger busy={busy} message="吊销后这把密钥不能再导入。已经导入到设备上的服务器不受影响。"
        onConfirm={async () => {
          if (!revoking) return;
          setBusy(true);
          try { await del(`/client-keys/${enc(revoking.id)}`); setRevoking(null); void load(); }
          catch (error) { toast(errorText(error), "error"); }
          finally { setBusy(false); }
        }} />
    </>
  );
}

function ClientKeySheet({ onClose }: { onClose: () => void }) {
  const toast = useStore((state) => state.toast);
  const [label, setLabel] = useState("");
  const [secret, setSecret] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const link = secret ? importLink(window.location.origin, secret) : "";

  const copy = (text: string) => {
    if (!navigator.clipboard) { toast("当前页面不能直接复制，请长按或拖选上面的文字复制", "error"); return; }
    void navigator.clipboard.writeText(text).then(() => toast("已复制"), () => toast("复制失败，请手动选择复制", "error"));
  };

  return (
    <Sheet open onOpenChange={(open) => !open && !busy && onClose()} title={secret ? "导入链接" : "新建接入密钥"}
      footer={secret ? <Button block onClick={() => copy(link)}>复制导入链接</Button> : (
        <Button block disabled={busy || !label.trim()} onClick={async () => {
          setBusy(true); setError(null);
          try { setSecret((await post<{ secret: string }>("/client-keys", { label: label.trim() })).secret); }
          catch (failure) { setError(errorText(failure)); }
          finally { setBusy(false); }
        }}>{busy ? <Spinner className="text-on-primary" /> : "生成"}</Button>
      )}>
      {secret ? (
        <div className="space-y-3">
          <p className="px-1 text-[14px] leading-relaxed text-text-2">在设备上打开 Lagoon「设置 → 从中枢导入」，粘贴下面的导入链接。<span className="text-error">链接只显示这一次</span>，关闭后无法再查看。</p>
          <p className="select-all break-all rounded-2xl bg-surface-2 px-4 py-3 font-mono text-[13px] text-text">{link}</p>
          <p className="px-1 text-[13px] leading-relaxed text-text-3">链接里的地址取自当前网页地址；设备需要能访问这个地址。如果设备要用别的地址访问中枢，在客户端里改填中枢地址，密钥部分（lgk1. 开头）保持不变。</p>
          <Button variant="secondary" block onClick={() => copy(secret)}>只复制密钥</Button>
        </div>
      ) : (
        <div className="space-y-4">
          <TextField label="备注" value={label} onChange={(e) => setLabel(e.target.value)} placeholder="例如 我的 Pixel" maxLength={60} autoFocus
            hint="用于在列表中区分设备；每台设备建议单独一把密钥，便于吊销。" />
          {error && <p className="px-1 text-[14px] text-error">{error}</p>}
        </div>
      )}
    </Sheet>
  );
}

function PushSettings() {
  const support = pushSupport();
  const toast = useStore((state) => state.toast);
  const [subscribed, setSubscribed] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { void currentSubscription().then((it) => setSubscribed(it != null)).catch(() => setSubscribed(false)); }, []);
  const permission = "Notification" in window ? Notification.permission : "default";

  let footer: string;
  if (support === "insecure") footer = "推送需要 HTTPS：通过 HTTPS 地址打开中枢（反向代理、自带证书或自签名证书）后再开启。";
  else if (support === "needs-install") footer = "iPhone / iPad 需要先在 Safari 中「分享 → 添加到主屏幕」，再从主屏幕打开 Lagoon 开启推送。";
  else if (support === "unsupported") footer = "当前浏览器不支持网页推送。";
  else if (permission === "denied") footer = "通知权限已被拒绝，请在系统或浏览器设置中允许本站通知。";
  else footer = "任务完成、失败，或等待授权、等待回答时推送到本设备。中枢需要能访问浏览器厂商的推送服务。";

  return (
    <ListGroup title="推送通知" footer={footer}>
      <ListItem icon={Bell} title="本设备接收推送" summary={subscribed ? "已开启" : "未开启"} trailing={busy ? <Spinner /> : (
        <Switch checked={!!subscribed} disabled={support !== "ok" || subscribed == null} label="本设备接收推送" onChange={async (on) => {
          setBusy(true);
          try {
            if (on) { await enablePush(); setSubscribed(true); toast("推送已开启"); } else { await disablePush(); setSubscribed(false); }
          } catch (error) {
            toast(errorText(error, "无法开启推送"), "error");
          } finally {
            setBusy(false);
          }
        }} />
      )} />
      {subscribed && <ListItem title="发送测试推送" onClick={async () => {
        try { await testPush(); toast("已发送，稍等片刻"); } catch (error) { toast(errorText(error), "error"); }
      }} />}
    </ListGroup>
  );
}

function InstallSettings() {
  const [available, setAvailable] = useState(canInstall());
  useEffect(() => onInstallChange(() => setAvailable(canInstall())), []);
  if (isStandalone()) return null;
  if (available) {
    return (
      <ListGroup title="安装应用" footer="安装后 Lagoon 会像普通应用一样从桌面或主屏幕打开。">
        <ListItem icon={Monitor} title="安装 Lagoon" chevron onClick={() => void install()} />
      </ListGroup>
    );
  }
  if (isIos()) {
    return (
      <ListGroup title="安装应用" footer={window.isSecureContext ? "在 Safari 中点「分享」→「添加到主屏幕」。从主屏幕打开后才能接收推送。" : "需要通过 HTTPS 打开中枢才能添加为应用。"}>
        <ListItem icon={Smartphone} title="添加到主屏幕" />
      </ListGroup>
    );
  }
  if (!window.isSecureContext) {
    return <ListGroup title="安装应用" footer="安装为应用需要通过 HTTPS 访问中枢。"><ListItem icon={Monitor} title="当前为 HTTP 访问" /></ListGroup>;
  }
  return null;
}

function TlsSettings({ auth }: { auth: AuthStatus | null }) {
  if (!auth?.tls?.selfSigned) return null;
  const httpsUrl = `https://${window.location.hostname}:${auth.tls.port}/`;
  return (
    <ListGroup title="HTTPS 证书" footer={`中枢使用自签名证书。下载并在每台设备上安装、信任这个证书后，用 ${httpsUrl} 打开即可安装应用和接收推送。iOS：设置 → 通用 → VPN 与设备管理 安装后，再到 通用 → 关于本机 → 证书信任设置 中开启完全信任。`}>
      <ListItem icon={Download} title="下载根证书" chevron onClick={() => { window.location.href = "/api/v1/tls/cert"; }} />
      {!auth.secure && <ListItem icon={ShieldCheck} title="切换到 HTTPS 地址" summary={httpsUrl} chevron onClick={() => { window.location.href = httpsUrl; }} />}
    </ListGroup>
  );
}

function ModelVisibilitySheet({ server, onClose }: { server: ServerView; onClose: () => void }) {
  const visibility = useStore((state) => state.ui.modelVisibility?.[server.serverId]);
  const updateUi = useStore((state) => state.updateUi);
  const [models, setModels] = useState<ModelInfo[] | null>(null);
  const directory = server.projects[0]?.directory ?? server.sessions[0]?.directory ?? "";
  useEffect(() => {
    get<{ models: ModelInfo[] }>(`/servers/${enc(server.serverId)}/choices?directory=${enc(directory)}`).then((it) => setModels(it.models)).catch(() => setModels([]));
  }, [server.serverId, directory]);
  const isVisible = (info: ModelInfo) => (visibility ? visibility[info.key] === true : !info.deprecated);
  const set = (info: ModelInfo, visible: boolean) => updateUi((ui) => {
    const current = ui.modelVisibility?.[server.serverId] ?? Object.fromEntries((models ?? []).filter((it) => !it.deprecated).map((it) => [it.key, true]));
    return { ...ui, modelVisibility: { ...ui.modelVisibility, [server.serverId]: { ...current, [info.key]: visible } } };
  });
  return (
    <Sheet open onOpenChange={(open) => !open && onClose()} title={`模型 · ${server.profile.name}`} wide
      footer={visibility ? <Button variant="secondary" block onClick={() => updateUi((ui) => {
        const next = { ...ui.modelVisibility };
        delete next[server.serverId];
        return { ...ui, modelVisibility: next };
      })}>恢复显示全部模型</Button> : undefined}>
      {models == null ? <div className="flex justify-center py-10"><Spinner size={22} /></div> : models.length === 0 ? <p className="py-8 text-center text-text-2">服务器没有提供模型目录</p> : (
        <div className="squircle overflow-hidden rounded-[22px] bg-surface-2 [&>*+*]:border-t [&>*+*]:border-divider">
          {models.map((info) => (
            <ListItem key={info.key} title={info.choice.label} summary={`${info.providerName} · ${info.key}${info.deprecated ? " · 已弃用" : ""}`}
              trailing={<Switch checked={isVisible(info)} label={info.choice.label} onChange={(on) => set(info, on)} />} />
          ))}
        </div>
      )}
    </Sheet>
  );
}

function PasswordSheet({ onClose }: { onClose: () => void }) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const toast = useStore((state) => state.toast);
  return (
    <Sheet open onOpenChange={(open) => !open && !busy && onClose()} title="修改访问密码"
      footer={<Button block disabled={busy || !current || !next} onClick={async () => {
        setBusy(true); setError(null);
        try {
          await post("/auth/password", { current, next });
          toast("密码已修改，其他设备需要重新登录");
          onClose();
        } catch (failure) {
          setError(errorText(failure));
        } finally {
          setBusy(false);
        }
      }}>{busy ? <Spinner className="text-on-primary" /> : "保存"}</Button>}>
      <div className="space-y-4">
        <TextField label="当前密码" type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" />
        <TextField label="新密码" type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" hint="没有长度或复杂度要求。" />
        {error && <p className="px-1 text-[14px] text-error">{error}</p>}
      </div>
    </Sheet>
  );
}
