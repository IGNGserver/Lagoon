// Fullscreen WebGL ocean. One fragment shader carries the whole "dive":
// uDepth = 0 is the sunlit surface (caustics + light shafts), uDepth = 1 is the
// abyss (dark water, drifting bioluminescent motes). The pointer stirs the current.

const VERT = `
attribute vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
`;

const FRAG = (octaves) => `
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform float uDepth;
uniform vec2 uMouse;
uniform float uEnergy;
#define OCT ${octaves}

float h21(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
vec2 h22(vec2 p) { float n = h21(p); return vec2(n, h21(p + n)); }

float vnoise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(h21(i), h21(i + vec2(1.0, 0.0)), u.x),
             mix(h21(i + vec2(0.0, 1.0)), h21(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
  float a = 0.5, s = 0.0;
  mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
  for (int i = 0; i < OCT; i++) { s += a * vnoise(p); p = m * p; a *= 0.5; }
  return s;
}

// Distance between the two nearest animated cell centres: thin bright seams = caustic web.
float seams(vec2 p, float t) {
  vec2 i = floor(p), f = fract(p);
  float d1 = 8.0, d2 = 8.0;
  for (int y = -1; y <= 1; y++) {
    for (int x = -1; x <= 1; x++) {
      vec2 g = vec2(float(x), float(y));
      vec2 o = h22(i + g);
      o = 0.5 + 0.45 * sin(t + 6.2831 * o);
      vec2 r = g + o - f;
      float d = dot(r, r);
      if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) { d2 = d; }
    }
  }
  return sqrt(d2) - sqrt(d1);
}

float caustics(vec2 p, float t) {
  vec2 w = vec2(fbm(p * 0.55 + t * 0.05), fbm(p * 0.55 - t * 0.04 + 3.1));
  p += (w - 0.5) * 1.5;
  float a = 1.0 - smoothstep(0.0, 0.2, seams(p, t * 0.55));
  float b = 1.0 - smoothstep(0.0, 0.16, seams(p * 1.65 + 7.3, t * 0.75));
  return a * a * a * 0.75 + b * b * b * 0.4;
}

void main() {
  vec2 uv = gl_FragCoord.xy / uRes;
  float asp = uRes.x / uRes.y;
  vec2 p = vec2((uv.x - 0.5) * asp, uv.y - 0.5);
  float t = uTime;
  float D = clamp(uDepth, 0.0, 1.0);
  float shallow = pow(1.0 - D, 3.2);

  // pointer: a soft vortex that bends the water around the cursor
  vec2 m = vec2((uMouse.x - 0.5) * asp, uMouse.y - 0.5);
  vec2 dm = p - m;
  float md2 = dot(dm, dm);
  float swirl = exp(-md2 * 10.0) * (0.25 + uEnergy);
  p += vec2(-dm.y, dm.x) * swirl * 0.32;

  // slow domain-warped currents (the "fluid art" layer)
  vec2 q = vec2(fbm(p * 1.25 + vec2(0.0, t * 0.03)), fbm(p * 1.25 + vec2(5.2, -t * 0.025)));
  vec2 r = vec2(fbm(p * 1.55 + q * 2.3 + vec2(1.7, 9.2) + t * 0.021),
                fbm(p * 1.55 + q * 2.3 + vec2(8.3, 2.8) - t * 0.017));
  float f = fbm(p * 1.15 + r * 1.9 + vec2(0.0, D * 3.0));

  vec3 abyss = vec3(0.008, 0.02, 0.05);
  vec3 deep = vec3(0.02, 0.06, 0.14);
  vec3 lit = vec3(0.05, 0.27, 0.4);
  float lightFromTop = clamp(uv.y * 1.2 - D * 1.9 + 0.02, 0.0, 1.0);
  vec3 col = mix(abyss, deep, smoothstep(0.0, 1.0, uv.y * 0.7 + 0.35 - D * 0.45));
  col = mix(col, lit, pow(lightFromTop, 1.6) * 0.95);

  vec3 indigo = vec3(0.28, 0.32, 0.84);
  vec3 cyan = vec3(0.35, 0.82, 0.9);
  vec3 teal = vec3(0.32, 0.88, 0.77);

  float streak = smoothstep(0.48, 0.95, f);
  col += mix(indigo, cyan, clamp(r.x * 1.2 - 0.1, 0.0, 1.0)) * streak * (0.14 + 0.1 * shallow);
  col += indigo * pow(q.y, 2.5) * (0.1 + 0.06 * D);

  // caustic web, strongest near the surface and the top of the viewport
  float ca = caustics(p * 2.3 + vec2(0.0, t * 0.02), t);
  col += vec3(0.5, 0.9, 1.0) * ca * smoothstep(0.1, 0.95, uv.y) * (0.03 + shallow * 0.42);

  // light shafts leaning in from above
  float ang = p.x * 1.15 + p.y * 0.38;
  float shafts = fbm(vec2(ang * 3.6, t * 0.07)) * fbm(vec2(ang * 8.5 + 3.0, t * 0.05 + 2.0));
  col += vec3(0.36, 0.74, 0.95) * pow(shafts, 1.8) * smoothstep(-0.3, 0.55, p.y + 0.15) * (0.04 + shallow * 0.5);

  // bioluminescent motes, two parallax layers, more of them the deeper we go
  for (int k = 0; k < 2; k++) {
    float sc = k == 0 ? 11.0 : 19.0;
    vec2 gp = p * sc + vec2(0.0, t * (k == 0 ? 0.16 : 0.1)) + vec2(D * 6.0 * float(k + 1), D * -14.0);
    vec2 gi = floor(gp);
    vec2 gf = fract(gp) - 0.5;
    vec2 o = (h22(gi) - 0.5) * 0.7;
    float on = step(0.86 - D * 0.12, h21(gi + 3.7));
    float tw = 0.5 + 0.5 * sin(t * (1.2 + h21(gi) * 2.0) + h21(gi + 9.1) * 30.0);
    float d = length(gf - o);
    float mote = on * tw * (smoothstep(0.07, 0.0, d) + 0.25 * smoothstep(0.22, 0.0, d));
    col += mix(teal, cyan, h21(gi + 1.3)) * mote * (0.2 + D * 0.9) * (k == 0 ? 1.0 : 0.6);
  }

  // the pointer carries a little light
  col += cyan * exp(-md2 * 16.0) * (0.035 + 0.12 * uEnergy);

  // vignette + dither
  vec2 vv = uv - 0.5;
  col *= 1.0 - 0.38 * dot(vv, vv) * 1.6;
  col += (h21(gl_FragCoord.xy + fract(t)) - 0.5) / 255.0;
  gl_FragColor = vec4(col, 1.0);
}
`;

export function startOcean(canvas, { reducedMotion = false } = {}) {
  const gl = canvas.getContext("webgl", { antialias: false, alpha: false, depth: false, stencil: false, powerPreference: "low-power", preserveDrawingBuffer: false });
  if (!gl) return null;

  const coarse = matchMedia("(pointer: coarse)").matches;
  const octaves = coarse ? 4 : 5;

  const compile = (type, src) => {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) { console.warn(gl.getShaderInfoLog(s)); return null; }
    return s;
  };
  const vs = compile(gl.VERTEX_SHADER, VERT);
  const fs = compile(gl.FRAGMENT_SHADER, FRAG(octaves));
  if (!vs || !fs) return null;
  const prog = gl.createProgram();
  gl.attachShader(prog, vs);
  gl.attachShader(prog, fs);
  gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) return null;
  gl.useProgram(prog);

  const buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const loc = gl.getAttribLocation(prog, "aPos");
  gl.enableVertexAttribArray(loc);
  gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);

  const U = (n) => gl.getUniformLocation(prog, n);
  const uRes = U("uRes"), uTime = U("uTime"), uDepth = U("uDepth"), uMouse = U("uMouse"), uEnergy = U("uEnergy");

  // Render below native resolution: the image is soft by nature, and this keeps phones cool.
  let scale = coarse ? 0.42 : 0.55;
  const state = { depth: 0, depthTarget: 0, mx: 0.62, my: 0.62, tx: 0.62, ty: 0.62, energy: 0, running: true, raf: 0 };

  function resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = Math.max(1, Math.round(innerWidth * dpr * scale));
    const h = Math.max(1, Math.round(innerHeight * dpr * scale));
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
      gl.viewport(0, 0, w, h);
    }
    gl.uniform2f(uRes, w, h);
  }

  function onPointer(e) {
    const nx = e.clientX / innerWidth;
    const ny = 1 - e.clientY / innerHeight;
    const v = Math.hypot(nx - state.tx, ny - state.ty);
    state.tx = nx;
    state.ty = ny;
    state.energy = Math.min(1, state.energy + v * 6);
  }

  const t0 = performance.now();
  let last = t0, slowFrames = 0;
  function frame(now) {
    state.raf = 0;
    const dt = Math.min(0.05, (now - last) / 1000);
    last = now;
    // adapt resolution if the GPU struggles
    if (dt > 0.034) { if (++slowFrames > 40 && scale > 0.3) { scale -= 0.07; slowFrames = 0; resize(); } }
    else slowFrames = Math.max(0, slowFrames - 1);

    state.depth += (state.depthTarget - state.depth) * Math.min(1, dt * 3.5);
    state.mx += (state.tx - state.mx) * Math.min(1, dt * 4);
    state.my += (state.ty - state.my) * Math.min(1, dt * 4);
    state.energy *= Math.pow(0.18, dt);

    gl.uniform1f(uTime, reducedMotion ? 12.0 : (now - t0) / 1000);
    gl.uniform1f(uDepth, state.depth);
    gl.uniform2f(uMouse, state.mx, state.my);
    gl.uniform1f(uEnergy, state.energy);
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    if (!reducedMotion && state.running) state.raf = requestAnimationFrame(frame);
  }
  const kick = () => { if (!state.raf) state.raf = requestAnimationFrame(frame); };

  resize();
  addEventListener("resize", () => { resize(); kick(); }, { passive: true });
  if (!reducedMotion) addEventListener("pointermove", onPointer, { passive: true });
  document.addEventListener("visibilitychange", () => {
    state.running = !document.hidden;
    if (state.running) { last = performance.now(); kick(); }
  });
  canvas.addEventListener("webglcontextlost", (e) => { e.preventDefault(); state.running = false; canvas.classList.remove("is-ready"); });

  kick();
  requestAnimationFrame(() => canvas.classList.add("is-ready"));

  return {
    setDepth(d) {
      state.depthTarget = d;
      if (reducedMotion) { state.depth = d; kick(); }
    },
  };
}
