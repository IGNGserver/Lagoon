import { startOcean } from "./ocean.js";
import { initCurrents } from "./currents.js";
import { initStory } from "./story.js";
import { initLive } from "./live.js";

const root = document.documentElement;
root.classList.remove("no-js");
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
const finePointer = matchMedia("(hover: hover) and (pointer: fine)").matches;
const MAX_DEPTH = 200;

/* ---------- ocean ---------- */
const ocean = startOcean(document.getElementById("ocean"), { reducedMotion });

/* ---------- depth: scroll position → metres, gauge marks, nav state ---------- */
const sections = [...document.querySelectorAll("main > section[data-depth]")];
const gauge = document.querySelector("[data-depth-gauge]");
const gaugeValue = gauge?.querySelector("[data-depth-value]");
const marksBox = gauge?.querySelector(".depth__marks");
const nav = document.querySelector("[data-nav]");
const navLinks = [...document.querySelectorAll(".nav__links a")];

const marks = sections.map((s) => {
  const m = document.createElement("span");
  m.className = "depth__mark";
  m.textContent = `${s.dataset.label} ${s.dataset.depth === "0" ? "0" : "−" + s.dataset.depth}`;
  m.style.top = `${(Number(s.dataset.depth) / MAX_DEPTH) * 100}%`;
  marksBox?.appendChild(m);
  return m;
});

let anchors = [];
function measure() {
  anchors = sections.map((s) => ({ top: s.offsetTop, depth: Number(s.dataset.depth), id: s.id }));
  // the surface reads 0 m while the page is at rest
  if (anchors.length) anchors[0].top = Math.max(anchors[0].top, innerHeight * 0.35);
}

function depthAt(y) {
  // piecewise-linear between section tops so each heading lands exactly on its stated depth
  const probe = y + innerHeight * 0.35;
  if (probe <= anchors[0].top) return 0;
  for (let i = 0; i < anchors.length - 1; i++) {
    const a = anchors[i], b = anchors[i + 1];
    if (probe < b.top) return a.depth + ((probe - a.top) / (b.top - a.top)) * (b.depth - a.depth);
  }
  const last = anchors[anchors.length - 1];
  const end = document.documentElement.scrollHeight - innerHeight * 0.65;
  return probe >= end ? MAX_DEPTH : last.depth;
}

let ticking = false;
let gaugeTimer = 0;
function onScroll() {
  if (ticking) return;
  ticking = true;
  requestAnimationFrame(() => {
    ticking = false;
    const y = scrollY;
    const d = Math.max(0, Math.min(MAX_DEPTH, depthAt(y)));
    const p = d / MAX_DEPTH;
    ocean?.setDepth(p);
    if (gauge) {
      gauge.style.setProperty("--p", p.toFixed(4));
      const v = Math.round(d);
      gaugeValue.textContent = v;
      if (v === 0) gaugeValue.setAttribute("data-zero", ""); else gaugeValue.removeAttribute("data-zero");
      marks.forEach((m, i) => m.classList.toggle("is-passed", d >= anchors[i].depth - 0.5));
      gauge.classList.add("show-marks");
      clearTimeout(gaugeTimer);
      gaugeTimer = setTimeout(() => gauge.classList.remove("show-marks"), 1200);
    }
    nav.classList.toggle("is-scrolled", y > 40);
    let active = "";
    for (const a of anchors) if (y + innerHeight * 0.4 >= a.top) active = a.id;
    navLinks.forEach((l) => l.classList.toggle("is-active", l.getAttribute("href") === `#${active}`));
    document.body.style.setProperty("--scroll", y.toFixed(0));
  });
}

measure();
onScroll();
addEventListener("scroll", onScroll, { passive: true });
addEventListener("resize", () => { measure(); onScroll(); }, { passive: true });
addEventListener("load", () => { measure(); onScroll(); });

/* ---------- mobile menu ---------- */
const menuBtn = document.querySelector("[data-menu-toggle]");
const menu = document.querySelector("[data-menu]");
function closeMenu() { menu.hidden = true; menuBtn.setAttribute("aria-expanded", "false"); }
menuBtn.addEventListener("click", () => {
  const open = menu.hidden;
  menu.hidden = !open;
  menuBtn.setAttribute("aria-expanded", String(open));
});
menu.addEventListener("click", (e) => { if (e.target.closest("a")) closeMenu(); });
addEventListener("keydown", (e) => { if (e.key === "Escape" && !menu.hidden) { closeMenu(); menuBtn.focus(); } });
document.addEventListener("click", (e) => { if (!menu.hidden && !nav.contains(e.target)) closeMenu(); });

/* ---------- reveal on scroll ---------- */
const revealIO = new IntersectionObserver((entries) => {
  for (const e of entries) if (e.isIntersecting) { e.target.classList.add("is-in"); revealIO.unobserve(e.target); }
}, { rootMargin: "0px 0px -8% 0px", threshold: 0.08 });
document.querySelectorAll(".reveal").forEach((el, i) => {
  el.style.transitionDelay = `${(i % 4) * 70}ms`;
  revealIO.observe(el);
});

/* ---------- glass specular highlight follows the pointer ---------- */
if (finePointer) {
  document.querySelectorAll("[data-glass]").forEach((el) => {
    el.addEventListener("pointermove", (e) => {
      const r = el.getBoundingClientRect();
      el.style.setProperty("--mx", `${e.clientX - r.left}px`);
      el.style.setProperty("--my", `${e.clientY - r.top}px`);
      el.style.setProperty("--spec", "1");
    });
    el.addEventListener("pointerleave", () => el.style.setProperty("--spec", "0.6"));
  });

  /* magnetic buttons */
  document.querySelectorAll("[data-magnetic]").forEach((el) => {
    el.addEventListener("pointermove", (e) => {
      const r = el.getBoundingClientRect();
      el.style.setProperty("--tx", `${((e.clientX - r.left) / r.width - 0.5) * 10}px`);
      el.style.setProperty("--ty", `${((e.clientY - r.top) / r.height - 0.5) * 8}px`);
    });
    el.addEventListener("pointerleave", () => { el.style.setProperty("--tx", "0px"); el.style.setProperty("--ty", "0px"); });
  });
}

/* ---------- hero phone: pointer tilt + scroll tilt ---------- */
const stage = document.querySelector("[data-tilt-stage]");
const phone = stage?.querySelector("[data-tilt]");
if (phone && !reducedMotion) {
  let px = 0, py = 0, sy = 0, raf = 0;
  const baseRy = parseFloat(getComputedStyle(phone).getPropertyValue("--ry")) || -16;
  const apply = () => {
    raf = 0;
    const ry = baseRy + px * 18;
    const rx = 6 - py * 12 + sy * 14;
    phone.style.setProperty("--ry", `${ry.toFixed(2)}deg`);
    phone.style.setProperty("--rx", `${rx.toFixed(2)}deg`);
    phone.style.setProperty("--gl", `${(px * 40).toFixed(1)}deg`);
  };
  const queue = () => { if (!raf) raf = requestAnimationFrame(apply); };
  if (finePointer) {
    addEventListener("pointermove", (e) => {
      if (scrollY > innerHeight) return;
      px = e.clientX / innerWidth - 0.5;
      py = e.clientY / innerHeight - 0.5;
      queue();
    }, { passive: true });
  }
  addEventListener("scroll", () => {
    if (scrollY > innerHeight * 1.2) return;
    sy = Math.min(1, scrollY / innerHeight);
    queue();
  }, { passive: true });
}

/* ---------- hero home screen: a quietly living session list ---------- */
(function heroLife() {
  const home = document.querySelector("[data-home]");
  if (!home || reducedMotion) return;
  const shortEl = home.querySelector("[data-home-short]");
  const rows = Object.fromEntries([...home.querySelectorAll("[data-row]")].map((r) => [r.dataset.row, r]));
  const st = (row) => row.querySelector(".st");
  const flash = (row) => { row.classList.add("is-flash"); setTimeout(() => row.classList.remove("is-flash"), 900); };
  // running, completed(unread) — mirrors "{running}跑·{completed}完·{waiting}待"
  const scenes = [
    () => { // d finishes → unread
      st(rows.d).className = "st st--unread"; rows.d.classList.add("is-bold"); flash(rows.d);
      shortEl.textContent = "1跑·2完·1待";
    },
    () => { // b gets approved → running
      st(rows.b).className = "st st--run"; rows.b.querySelector("em")?.remove(); flash(rows.b);
      shortEl.textContent = "2跑·2完";
    },
    () => { // c read → plain
      st(rows.c).className = "st"; rows.c.classList.remove("is-bold"); flash(rows.c);
      shortEl.textContent = "2跑·1完";
    },
    () => { // reset to the initial frame
      st(rows.d).className = "st st--run"; rows.d.classList.remove("is-bold");
      st(rows.b).className = "st st--wait";
      if (!rows.b.querySelector("em")) { const em = document.createElement("em"); em.textContent = "待授权"; rows.b.appendChild(em); }
      st(rows.c).className = "st st--unread"; rows.c.classList.add("is-bold");
      shortEl.textContent = "2跑·1完·1待";
    },
  ];
  shortEl.textContent = "2跑·1完·1待";
  let i = 0;
  setInterval(() => { if (!document.hidden && scrollY < innerHeight) scenes[i++ % scenes.length](); }, 3200);
})();

/* ---------- sections ---------- */
const currents = document.querySelector("[data-currents]");
if (currents) initCurrents(currents, { reducedMotion });
const story = document.getElementById("session");
if (story) initStory(story, { reducedMotion });
const live = document.querySelector("[data-live]");
if (live) initLive(live);

/* ---------- copy command ---------- */
document.querySelectorAll("[data-copy]").forEach((b) => {
  b.addEventListener("click", async () => {
    const src = b.parentElement.querySelector("[data-copy-src]");
    const label = b.querySelector("[data-copy-label]");
    try {
      await navigator.clipboard.writeText(src.textContent.trim());
      label.textContent = "已复制";
    } catch {
      const range = document.createRange();
      range.selectNodeContents(src);
      getSelection().removeAllRanges();
      getSelection().addRange(range);
      label.textContent = "已选中";
    }
    b.classList.add("is-done");
    setTimeout(() => { label.textContent = "复制"; b.classList.remove("is-done"); }, 1800);
  });
});
