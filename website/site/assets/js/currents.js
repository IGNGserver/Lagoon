// "Currents": the phone and its OpenCode servers as glowing nodes joined by light threads.
// Stable (v0.2.4): only the current server is monitored live; tapping another switches to it.
// Preview (v0.2.5-rc): every saved server stays connected; tapping one simulates a drop + reconnect.

const NS = "http://www.w3.org/2000/svg";
const PHONE = { x: 400, y: 452 };
const SERVERS = [
  { id: "mine", name: "我的 OpenCode", meta: "https://oc.example.com", x: 150, y: 120 },
  { id: "ws", name: "工作站", meta: "opencode pair 配对链接", x: 400, y: 66 },
  { id: "nas", name: "家用 NAS", meta: "http://192.168.1.100:4096", x: 650, y: 132 },
];

const el = (tag, attrs = {}, parent) => {
  const n = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v);
  if (parent) parent.appendChild(n);
  return n;
};

export function initCurrents(root, { reducedMotion = false } = {}) {
  const svg = root.querySelector("svg");
  const gThreads = svg.querySelector("[data-threads]");
  const gPackets = svg.querySelector("[data-packets]");
  const gNodes = svg.querySelector("[data-nodes]");
  const status = root.querySelector("[data-currents-status]");
  const modeButtons = root.querySelectorAll("[data-mode]");

  let mode = "stable";
  let current = "mine";
  const conn = new Map(); // id -> "live" | "idle" | "recon"
  const packets = [];

  const items = SERVERS.map((s) => {
    const c1x = s.x + (PHONE.x - s.x) * 0.1;
    const c2x = PHONE.x + (s.x - PHONE.x) * 0.2;
    const d = `M${PHONE.x} ${PHONE.y - 50} C ${c2x} ${PHONE.y - 170}, ${c1x} ${s.y + 140}, ${s.x} ${s.y + 34}`;
    const glow = el("path", { d, class: "thread thread--glow" }, gThreads);
    const path = el("path", { d, class: "thread" }, gThreads);

    const g = el("g", { class: "node", transform: `translate(${s.x} ${s.y})`, tabindex: "0", role: "button" }, gNodes);
    el("circle", { r: 46, class: "node__halo" }, g);
    el("circle", { r: 30, class: "node__orb" }, g);
    el("circle", { r: 6, class: "node__core" }, g);
    // labels sit above each orb; threads arrive from below
    const name = el("text", { y: -92, "text-anchor": "middle", class: "node__name" }, g);
    name.textContent = s.name;
    const meta = el("text", { y: -74, "text-anchor": "middle", class: "node__meta" }, g);
    meta.textContent = s.meta;
    const st = el("text", { y: -56, "text-anchor": "middle", class: "node__state" }, g);
    const item = { ...s, path, glow, g, st, len: 0 };
    g.addEventListener("click", () => tap(item));
    g.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); tap(item); } });
    return item;
  });

  items.forEach((it) => { it.len = it.path.getTotalLength(); });

  function label(it) {
    const s = conn.get(it.id);
    const text = s === "live" ? (mode === "stable" ? "当前服务器 · 实时监控" : "已连接 · 实时同步")
      : s === "recon" ? "断线 · 自动重连中…" : "已保存 · 点按切换";
    it.st.textContent = text;
    it.g.setAttribute("aria-label", `${it.name}，${text}`);
    it.g.classList.toggle("is-idle", s === "idle");
    it.g.classList.toggle("is-recon", s === "recon");
    it.path.classList.toggle("is-off", s === "idle");
    it.glow.classList.toggle("is-off", s !== "live");
    it.path.classList.toggle("is-recon", s === "recon");
  }

  function apply() {
    for (const it of items) {
      if (mode === "stable") conn.set(it.id, it.id === current ? "live" : "idle");
      else if (conn.get(it.id) !== "recon") conn.set(it.id, "live");
      label(it);
    }
  }

  const timers = new Map();
  function tap(it) {
    if (mode === "stable") {
      if (it.id === current) { say(`「${it.name}」已是当前服务器，事件流保持连接。`); return; }
      const prev = items.find((x) => x.id === current);
      current = it.id;
      apply();
      say(`已切换到「${it.name}」。「${prev.name}」的本地监控随之停止，远端任务继续运行。`);
    } else {
      clearTimeout(timers.get(it.id));
      conn.set(it.id, "recon");
      label(it);
      say(`「${it.name}」断线，正在独立重连；其他服务器不受影响。`);
      timers.set(it.id, setTimeout(() => {
        conn.set(it.id, "live");
        label(it);
        say(`「${it.name}」已恢复：重读控制状态，补齐断线期间的完成、权限与表单变化。`);
      }, 2400));
    }
  }

  function say(text) { status.textContent = text; }

  modeButtons.forEach((b) => b.addEventListener("click", () => {
    mode = b.dataset.mode;
    modeButtons.forEach((x) => { const on = x === b; x.classList.toggle("is-on", on); x.setAttribute("aria-pressed", String(on)); });
    timers.forEach((t) => clearTimeout(t));
    conn.clear();
    apply();
    say(mode === "stable"
      ? "正式版：保存多台服务器，本地只实时监控当前服务器。点按节点切换。"
      : "预览版：全部服务器保持持续连接，断线各自恢复。点按节点模拟断线。");
  }));

  apply();

  // packets travelling the threads: teal = SSE events down to the phone, indigo = requests up
  if (!reducedMotion) {
    const spawn = (it, up) => {
      const c = el("circle", { r: up ? 3 : 3.6, class: up ? "packet packet--up" : "packet" }, gPackets);
      packets.push({ it, up, t: 0, c, speed: 0.35 + Math.random() * 0.25 });
    };
    let last = performance.now(), acc = 0, visible = false;
    new IntersectionObserver(([e]) => { visible = e.isIntersecting; if (visible) { last = performance.now(); requestAnimationFrame(tick); } }).observe(svg);
    function tick(now) {
      if (!visible) return;
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      acc += dt;
      if (acc > 0.32) {
        acc = 0;
        const live = items.filter((x) => conn.get(x.id) === "live");
        if (live.length) spawn(live[(Math.random() * live.length) | 0], Math.random() < 0.3);
      }
      for (let i = packets.length - 1; i >= 0; i--) {
        const p = packets[i];
        p.t += dt * p.speed;
        const alive = conn.get(p.it.id) === "live";
        if (p.t >= 1 || !alive) { p.c.remove(); packets.splice(i, 1); continue; }
        const at = p.up ? p.t : 1 - p.t;
        const pt = p.it.path.getPointAtLength(at * p.it.len);
        p.c.setAttribute("cx", pt.x);
        p.c.setAttribute("cy", pt.y);
        p.c.setAttribute("opacity", Math.sin(p.t * Math.PI).toFixed(3));
      }
      requestAnimationFrame(tick);
    }
  }
}
