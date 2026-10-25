// Live Updates simulator. The wording and stage rules mirror the app's
// core/TaskSummary.kt + core/LiveUpdateContent.kt (v0.2.4):
//   stage  ACTIVE  running > 0 || waiting > 0
//          SETTLED completed > 0 || failed > 0
//          EMPTY   otherwise
//   short  "{running}跑·{completed}完" + "·{waiting}待" + "·{failed}败"
//   items  ordered waiting > running > failed > completed, max 3 lines + "另有 N 个任务"

const TITLES = [
  "重构离线缓存的截断策略",
  "升级 Gradle 版本目录",
  "修复 SSE 重连后的计数",
  "为通知深链补充回归测试",
  "对齐官方时间线投影规则",
  "排查子会话父链汇总",
  "整理模型选择器的标签",
  "补齐权限表单的回归用例",
];

const PHASE = { wait: "待处理", run: "运行中", fail: "失败", done: "已完成" };
const ORDER = { wait: 0, run: 1, fail: 2, done: 3 };

export function initLive(root) {
  const $ = (s) => root.querySelector(s);
  const pill = $("[data-live-pill]"), short = $("[data-live-short]");
  const card = $("[data-live-card]"), title = $("[data-live-title]"), text = $("[data-live-text]");
  const track = $("[data-live-track]"), lines = $("[data-live-lines]"), stageTag = $("[data-live-stage]");
  const empty = $("[data-live-empty]"), caption = $("[data-live-caption]");
  const buttons = Object.fromEntries([...root.querySelectorAll("[data-act]")].map((b) => [b.dataset.act, b]));
  const stageRows = root.querySelectorAll("[data-stage-row]");
  const osButtons = root.querySelectorAll("[data-os]");

  let os = "16";
  let next = 0;
  // each task: { id, title, phase: run|wait|done|fail, at }
  let tasks = [];
  const add = (phase) => { const t = { id: next, title: TITLES[next % TITLES.length], phase, at: performance.now() + next }; next++; tasks.push(t); return t; };
  add("run");

  function summary() {
    const c = { run: 0, wait: 0, done: 0, fail: 0 };
    tasks.forEach((t) => c[t.phase]++);
    return c;
  }

  function render() {
    const c = summary();
    const stage = c.run > 0 || c.wait > 0 ? "ACTIVE" : c.done > 0 || c.fail > 0 ? "SETTLED" : "EMPTY";

    let head = "";
    if (c.wait > 0) head = `${c.wait} 个任务待你处理`;
    else if (c.run > 0) head = `${c.run} 个任务运行中`;
    else if (c.fail > 0 && c.done > 0) head = `${c.done} 个已完成，${c.fail} 个失败`;
    else if (c.fail > 0) head = `${c.fail} 个任务失败`;
    else if (c.done > 0) head = `${c.done} 个任务已完成`;

    let status = `${c.run}个运行中，${c.done}个已完成`;
    if (c.wait > 0) status += `，${c.wait}个待回复`;
    if (c.fail > 0) status += `，${c.fail}个失败`;

    let s = `${c.run}跑·${c.done}完`;
    if (c.wait > 0) s += `·${c.wait}待`;
    if (c.fail > 0) s += `·${c.fail}败`;

    // status-bar chip only exists for an ACTIVE overview on Android 16
    const showPill = stage === "ACTIVE" && os === "16";
    pill.classList.toggle("is-hidden", !showPill);
    short.textContent = s;

    card.classList.toggle("is-gone", stage === "EMPTY");
    empty.hidden = stage !== "EMPTY";
    title.textContent = head;
    text.textContent = status;
    stageTag.dataset.stage = stage;
    stageTag.textContent = stage === "ACTIVE" ? (os === "16" ? "实时更新 · 常驻" : "常驻通知") : stage === "SETTLED" ? "普通通知 · 可划掉" : "";

    // progress only while ACTIVE: Android 16 → segmented composition, older → indeterminate bar
    track.replaceChildren();
    track.className = "notif__track";
    track.hidden = stage !== "ACTIVE";
    if (stage === "ACTIVE" && os === "16") {
      for (const [k, cls] of [["run", "seg-run"], ["wait", "seg-wait"], ["done", "seg-done"], ["fail", "seg-fail"]]) {
        if (!c[k]) continue;
        const span = document.createElement("span");
        span.className = cls;
        span.style.setProperty("--n", c[k]);
        track.appendChild(span);
      }
    } else if (stage === "ACTIVE") {
      track.classList.add("is-indeterminate");
    }

    const sorted = [...tasks].sort((a, b) => ORDER[a.phase] - ORDER[b.phase] || b.at - a.at);
    lines.replaceChildren();
    sorted.slice(0, 3).forEach((t) => {
      const li = document.createElement("li");
      const tag = document.createElement("span");
      tag.className = `tag-${t.phase}`;
      tag.textContent = PHASE[t.phase];
      li.append(tag, ` · ${t.title}`);
      lines.appendChild(li);
    });
    const hidden = tasks.length - Math.min(3, tasks.length);
    if (hidden > 0) {
      const li = document.createElement("li");
      li.className = "more";
      li.textContent = `另有 ${hidden} 个任务`;
      lines.appendChild(li);
    }

    stageRows.forEach((r) => r.classList.toggle("is-current", r.dataset.stageRow === stage));
    buttons.ask.disabled = c.run === 0;
    buttons.answer.disabled = c.wait === 0;
    buttons.complete.disabled = c.run === 0;
    buttons.fail.disabled = c.run === 0;
    buttons.read.disabled = c.done + c.fail === 0;
    buttons.start.disabled = tasks.length >= 8;
    caption.textContent = os === "16"
      ? "示意图：Android 16 的胶囊、卡片与锁屏外观由系统绘制，以设备实际显示为准。"
      : "示意图：Android 15 及以下没有实时更新胶囊，总览是一条普通通知，进行中时显示不确定进度条。";
  }

  const newest = (phase) => tasks.filter((t) => t.phase === phase).sort((a, b) => b.at - a.at)[0];
  const oldest = (phase) => tasks.filter((t) => t.phase === phase).sort((a, b) => a.at - b.at)[0];

  const act = {
    start() { add("run"); },
    ask() { const t = oldest("run"); if (t) { t.phase = "wait"; t.at = performance.now(); } },
    answer() { const t = oldest("wait"); if (t) t.phase = "run"; },
    complete() { const t = oldest("run"); if (t) { t.phase = "done"; t.at = performance.now(); } },
    fail() { const t = newest("run"); if (t) { t.phase = "fail"; t.at = performance.now(); } },
    // opening a finished session marks its unread result as read → it leaves the overview
    read() { const t = newest("fail") || newest("done"); if (t) tasks = tasks.filter((x) => x !== t); },
  };

  root.addEventListener("click", (e) => {
    const b = e.target.closest("[data-act]");
    if (!b || b.disabled) return;
    act[b.dataset.act]();
    render();
  });
  osButtons.forEach((b) => b.addEventListener("click", () => {
    os = b.dataset.os;
    osButtons.forEach((x) => { const on = x === b; x.classList.toggle("is-on", on); x.setAttribute("aria-pressed", String(on)); });
    render();
  }));

  render();
}
