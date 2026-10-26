import { useState, type FormEvent } from "react";
import { ApiError, post } from "@/api/http";
import type { AuthStatus } from "@/api/types";
import { Button, Spinner, TextField } from "@/components/ui";
import { BrandMark } from "@/components/BrandMark";

/** Sign in with the hub's single password, or set it the first time with the code from the container log. */
export function LoginPage({ status, onSignedIn }: { status: AuthStatus; onSignedIn: () => void }) {
  const setup = status.setupRequired;
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!password || busy) return;
    setBusy(true);
    setError(null);
    try {
      await post(setup ? "/auth/setup" : "/auth/login", setup ? { code, password } : { password });
      onSignedIn();
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "登录失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="safe-top safe-bottom flex min-h-full items-center justify-center bg-bg px-6">
      <form onSubmit={submit} className="w-full max-w-[380px] py-10">
        <div className="mb-8 flex flex-col items-center text-center">
          <BrandMark size={64} />
          <h1 className="mt-5 text-[26px] font-semibold">Lagoon 中枢</h1>
          <p className="mt-2 text-[14px] leading-relaxed text-text-2">
            {setup ? "首次使用，请设置访问密码。初始化码在中枢容器的日志里。" : "输入访问密码以继续"}
          </p>
        </div>
        <div className="space-y-4">
          {setup && (
            <TextField label="初始化码" value={code} onChange={(e) => setCode(e.target.value)} autoCapitalize="characters"
              autoComplete="one-time-code" placeholder="XXXX-XXXX" />
          )}
          <TextField
            label={setup ? "新密码" : "访问密码"}
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={setup ? "new-password" : "current-password"}
            autoFocus={!setup}
            hint={setup ? "没有长度或复杂度要求。中枢可以操作你的开发机，请只在可信网络中暴露它。" : undefined}
          />
          {error && <p className="px-1 text-[14px] text-error">{error}</p>}
          <Button type="submit" size="lg" block disabled={busy || !password || (setup && !code)}>
            {busy ? <Spinner className="text-on-primary" /> : setup ? "设置并进入" : "登录"}
          </Button>
        </div>
        <p className="mt-8 text-center text-[12px] text-text-3">v{status.version}</p>
      </form>
    </main>
  );
}
