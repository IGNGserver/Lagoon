import { AlertCircle, ShieldAlert } from "lucide-react";
import { useMemo, useState } from "react";
import { enc, errorText, post, serverPath } from "@/api/http";
import type { PermissionRequest, QuestionPrompt, QuestionRequest } from "@/api/types";
import { useStore } from "@/store";
import { Button, ConfirmDialog, Spinner, cx } from "./ui";

async function act(path: string, body: unknown): Promise<boolean> {
  try {
    await post(path, body);
    return true;
  } catch (error) {
    useStore.getState().toast(errorText(error), "error");
    return false;
  }
}

/** A pending permission: 拒绝 / 允许一次, and 始终允许 (saved on the server) when the server offers a scope. */
export function PermissionCard({ serverId, request }: { serverId: string; request: PermissionRequest }) {
  const [pending, setPending] = useState<string | null>(null);
  const [confirmSave, setConfirmSave] = useState(false);
  const dangerous = /shell|write|delete|bash|edit/i.test(request.action);
  const reply = async (value: string) => {
    setPending(value);
    await act(`${serverPath(serverId)}/permissions/${enc(request.id)}`, { reply: value });
    setPending(null);
    setConfirmSave(false);
  };
  return (
    <div className={cx("squircle rounded-[22px] p-4", dangerous ? "bg-warning-subtle" : "bg-sheet")}>
      <div className="flex items-center gap-2 text-[13px]">
        <ShieldAlert size={16} className="text-warning" />
        <span className="font-medium text-warning">{dangerous ? "需确认关键操作" : "等待操作授权"}</span>
        <span className="ml-auto font-semibold text-primary">{request.action || "操作"}</span>
      </div>
      <p className="mt-2.5 text-[15px] font-semibold text-chat-text">服务器正在请求执行动作：</p>
      <pre className="code-block scroll-area mt-2 max-h-48 overflow-auto rounded-xl bg-code px-3 py-2 text-chat-text">{request.detail}</pre>
      {request.always.length > 0 && <p className="mt-2.5 text-[12.5px] text-chat-text-2">选择「始终允许」将在服务器保存规则：{request.always.join(", ")}</p>}
      <div className="mt-3 flex gap-2">
        <Button variant="secondary" block disabled={pending != null} onClick={() => reply("reject")}>{pending === "reject" ? <Spinner /> : "拒绝"}</Button>
        <Button block disabled={pending != null} onClick={() => reply("once")}>{pending === "once" ? <Spinner className="text-on-primary" /> : "允许一次"}</Button>
      </div>
      {request.always.length > 0 && (
        <Button variant="ghost" block className="mt-2" disabled={pending != null} onClick={() => setConfirmSave(true)}>始终允许</Button>
      )}
      <ConfirmDialog open={confirmSave} onOpenChange={setConfirmSave} title="保存项目权限规则" confirm="保存并允许" busy={pending === "always"}
        message={`规则将保存在服务器项目中，适用于后续任务。\n${request.always.join("\n")}`} onConfirm={() => reply("always")} />
    </div>
  );
}

function field(question: QuestionPrompt): Record<string, unknown> | null {
  if (!question.field.trim()) return null;
  try {
    const value = JSON.parse(question.field);
    return typeof value === "object" && value !== null ? value : null;
  } catch {
    return null;
  }
}

function defaultAnswers(question: QuestionPrompt): string[] {
  const raw = field(question)?.default;
  if (raw == null) return [];
  return Array.isArray(raw) ? raw.filter((it) => it != null).map(String) : [String(raw)];
}

/** Port of Forms.kt `formVisible`: hidden fields and unmet `when` conditions are skipped. */
function formVisible(spec: Record<string, unknown>, values: Map<string, string[]>): boolean {
  if (spec.hidden === true) return false;
  const conditions = Array.isArray(spec.when) ? (spec.when as Record<string, unknown>[]) : [];
  return conditions.every((condition) => {
    const expected = condition.value;
    const equal = (values.get(String(condition.key ?? "")) ?? []).some((value) => typeof expected === "number" ? Number(value) === expected : value === String(expected));
    return condition.op === "neq" ? !equal : equal;
  });
}

/** A question or form from the agent, paged one prompt at a time like the phone. */
export function QuestionCard({ serverId, request }: { serverId: string; request: QuestionRequest }) {
  const [answers, setAnswers] = useState<string[][]>(() => request.questions.map((q) => defaultAnswers(q).filter((value) => q.options.some((it) => it.value === value))));
  const [custom, setCustom] = useState<string[]>(() => request.questions.map((q) => {
    const first = defaultAnswers(q)[0];
    return first != null && !q.options.some((it) => it.value === first) ? first : "";
  }));
  const [page, setPage] = useState(0);
  const [pending, setPending] = useState(false);

  const rows = answers.map((list, index) => {
    const text = custom[index]?.trim();
    if (!text) return list;
    return request.questions[index].multiple ? [...list, text] : [text];
  });
  const values = useMemo(() => new Map(request.questions.map((q, index) => [String(field(q)?.key ?? ""), rows[index]] as [string, string[]])), [request.questions, rows]);
  const visible = request.questions.map((q, index) => ({ q, index })).filter(({ q }) => {
    const spec = request.form ? field(q) : null;
    return !spec || formVisible(spec, values);
  }).map((it) => it.index);
  const lastPage = Math.max(0, visible.length - 1);
  const currentPage = Math.min(page, lastPage);
  const index = visible[currentPage];
  const question = index != null ? request.questions[index] : null;
  const spec = question ? field(question) : null;

  const answered = (i: number) => {
    const s = field(request.questions[i]);
    if (s?.type === "external") return true;
    const type = s?.type;
    const customAllowed = request.questions[i].custom || type === "string" || type === "number" || type === "integer";
    return answers[i].length > 0 || (customAllowed && !!custom[i]?.trim());
  };
  const required = (i: number) => (request.form ? field(request.questions[i])?.required === true : true);
  const ready = index == null || !required(index) || answered(index);
  const canSubmit = !pending && (request.form ? visible.every((i) => !required(i) || answered(i)) : request.questions.every((_, i) => answered(i)));

  const choose = (i: number, value: string, multiple: boolean) => {
    setAnswers((current) => current.map((list, n) => n !== i ? list : multiple ? (list.includes(value) ? list.filter((it) => it !== value) : [...list, value]) : [value]));
    if (!multiple) setCustom((current) => current.map((it, n) => (n === i ? "" : it)));
  };

  const submit = async () => {
    setPending(true);
    await act(`${serverPath(serverId)}/questions/${enc(request.id)}`, { answers: rows });
    setPending(false);
  };
  const reject = async () => {
    setPending(true);
    await act(`${serverPath(serverId)}/questions/${enc(request.id)}/reject`, {});
    setPending(false);
  };

  return (
    <div className="squircle rounded-[22px] bg-sheet p-4">
      <div className="flex items-center gap-2 text-[13px] font-medium text-warning"><AlertCircle size={16} />需要你的回答
        {visible.length > 1 && <span className="ml-auto font-normal text-chat-text-2">第 {currentPage + 1}/{visible.length} 项</span>}
      </div>
      {question == null ? (
        <p className="mt-3 text-[14px] text-chat-text-2">这个表单暂无可填写的字段。</p>
      ) : (
        <div className="mt-3">
          <p className="whitespace-pre-wrap text-[15px] font-semibold text-chat-text">{question.title}</p>
          {spec?.type === "external" ? (
            typeof spec.url === "string" && spec.url.startsWith("https://")
              ? <a href={spec.url} target="_blank" rel="noopener noreferrer" className="mt-2 inline-block text-[14px] text-primary">打开外部表单页面</a>
              : <p className="mt-2 text-[12.5px] text-warning">外部页面仅支持 HTTPS，请在服务器确认此项</p>
          ) : (
            <div className="mt-2 space-y-1">
              {question.options.map((option) => {
                const checked = answers[index!].includes(option.value);
                return (
                  <label key={option.value} className="flex cursor-pointer items-start gap-3 rounded-xl px-1 py-2 hover:bg-bubble/50">
                    <input type={question.multiple ? "checkbox" : "radio"} name={`${request.id}-${index}`} checked={checked}
                      onChange={() => choose(index!, option.value, question.multiple)} className="mt-1 size-4 accent-[var(--primary)]" />
                    <span>
                      <span className="block text-[14.5px] text-chat-text">{option.label}</span>
                      {option.description && <span className="block text-[12.5px] text-chat-text-2">{option.description}</span>}
                    </span>
                  </label>
                );
              })}
              {question.custom && (
                <input value={custom[index!]} onChange={(e) => setCustom((current) => current.map((it, n) => (n === index ? e.target.value : it)))}
                  placeholder="输入自定义回答…" className="squircle mt-1 h-11 w-full rounded-xl bg-input px-3 text-[15px] text-chat-text outline-none placeholder:text-chat-faint" />
              )}
            </div>
          )}
        </div>
      )}
      <div className="mt-4 flex gap-2">
        <Button variant="secondary" block disabled={pending} onClick={reject}>取消</Button>
        {currentPage > 0 && <Button variant="secondary" block disabled={pending} onClick={() => setPage(currentPage - 1)}>上一页</Button>}
        {currentPage < lastPage
          ? <Button block disabled={pending || !ready} onClick={() => setPage(currentPage + 1)}>下一页</Button>
          : <Button block disabled={!canSubmit} onClick={submit}>{pending ? <Spinner className="text-on-primary" /> : "提交回答"}</Button>}
      </div>
    </div>
  );
}
