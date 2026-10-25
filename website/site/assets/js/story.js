// Session scrollytelling: the steps on the left drive the chat screen on the sticky phone.

export function initStory(section, { reducedMotion = false } = {}) {
  const screen = section.querySelector("[data-chat]");
  const steps = [...section.querySelectorAll("[data-step]")];
  const dots = [...section.querySelectorAll("[data-dot]")];
  const shows = [...screen.querySelectorAll("[data-show]")];
  const composer = screen.querySelector("[data-composer]");
  const btn = screen.querySelector("[data-composer-btn]");
  const elapsed = screen.querySelector("[data-elapsed]");
  let at = 0;
  let typing = 0;
  let clock = 0;

  const PLACEHOLDER = "描述你的任务…";
  const STEER = "顺便把截断阈值写进设置页说明。";

  function type(text, done) {
    clearInterval(typing);
    if (reducedMotion) { composer.textContent = text; done?.(); return; }
    composer.classList.add("is-typing");
    let i = 0;
    composer.textContent = "";
    typing = setInterval(() => {
      composer.textContent = text.slice(0, ++i);
      if (i >= text.length) { clearInterval(typing); setTimeout(() => done?.(), 380); }
    }, 55);
  }

  function resetComposer() {
    clearInterval(typing);
    composer.classList.remove("is-typing");
    composer.textContent = PLACEHOLDER;
  }

  function set(n) {
    if (n === at) return;
    const prev = at;
    at = n;
    screen.dataset.at = String(n);
    steps.forEach((s) => s.classList.toggle("is-active", Number(s.dataset.step) === n));
    dots.forEach((d) => d.classList.toggle("is-on", Number(d.dataset.dot) === n));

    shows.forEach((el) => {
      const k = Number(el.dataset.show);
      // the late steering message waits for its typing animation
      if (el.classList.contains("msg-user--late") && n === 4 && prev < 4) return;
      el.classList.toggle("is-shown", k <= n && !(k === 4 && n < 4));
    });
    screen.classList.toggle("is-trace-open", n === 2);
    screen.classList.toggle("is-allowed", n >= 4);
    btn.classList.toggle("is-stop", n >= 2);

    resetComposer();
    clearInterval(clock);
    if (n === 4) {
      const late = screen.querySelector(".msg-user--late");
      if (prev < 4) {
        type(STEER, () => { resetComposer(); late.classList.add("is-shown"); });
      } else {
        late.classList.add("is-shown");
      }
      let sec = 72;
      clock = setInterval(() => { sec++; elapsed.textContent = `${Math.floor(sec / 60)} 分 ${String(sec % 60).padStart(2, "0")} 秒`; }, 1000);
    }
  }

  // the user's first message "types" in when step 1 first becomes active
  const first = screen.querySelector('.msg-user[data-show="1"]');
  first.classList.remove("is-shown");

  let primed = false;
  const io = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (!e.isIntersecting) continue;
      const n = Number(e.target.dataset.step);
      if (n === 1 && !primed) {
        primed = true;
        set(1);
        first.classList.remove("is-shown");
        type(first.textContent.trim(), () => { resetComposer(); if (at >= 1) first.classList.add("is-shown"); });
      } else {
        set(n);
        if (n >= 1) first.classList.add("is-shown");
      }
    }
  }, {
    // narrow screens pin the phone on top, so a caption counts once it reaches the lower band
    rootMargin: matchMedia("(max-width: 960px)").matches ? "-70% 0px -14% 0px" : "-45% 0px -45% 0px",
  });
  steps.forEach((s) => io.observe(s));
}
