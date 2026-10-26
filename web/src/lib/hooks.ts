import { useEffect, useRef, useState, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent } from "react";
import { useStore, type ThemeMode } from "@/store";

export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const list = window.matchMedia(query);
    const update = () => setMatches(list.matches);
    update();
    list.addEventListener("change", update);
    return () => list.removeEventListener("change", update);
  }, [query]);
  return matches;
}

export const useWide = () => useMediaQuery("(min-width: 1024px)");

function resolve(mode: ThemeMode, systemDark: boolean): "light" | "dark" {
  return mode === "system" ? (systemDark ? "dark" : "light") : mode;
}

/** Applies the theme to <html> and the browser chrome (status bar / title bar colour). */
export function useTheme(): void {
  const mode = useStore((state) => state.theme);
  const systemDark = useMediaQuery("(prefers-color-scheme: dark)");
  useEffect(() => {
    const theme = resolve(mode, systemDark);
    document.documentElement.dataset.theme = theme;
    document.querySelectorAll('meta[name="theme-color"]').forEach((meta) => meta.remove());
    const meta = document.createElement("meta");
    meta.name = "theme-color";
    meta.content = theme === "dark" ? "#000000" : "#f7f7f7";
    document.head.appendChild(meta);
  }, [mode, systemDark]);
}

/** Re-renders every [interval] ms, for relative times and offline TTLs. */
export function useNow(interval = 60_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), interval);
    return () => window.clearInterval(timer);
  }, [interval]);
  return now;
}

/**
 * Keeps the layout above the on-screen keyboard. Android Chrome resizes the viewport itself
 * (`interactive-widget=resizes-content`); iOS Safari only shrinks the visual viewport, so the app
 * height follows it there.
 */
export function useVisualViewportHeight(): void {
  useEffect(() => {
    const viewport = window.visualViewport;
    if (!viewport) return;
    const update = () => {
      document.documentElement.style.setProperty("--app-height", `${Math.round(viewport.height)}px`);
      // iOS scrolls the whole layout up behind the keyboard; keep it pinned.
      if (viewport.offsetTop > 0) window.scrollTo(0, 0);
    };
    update();
    viewport.addEventListener("resize", update);
    viewport.addEventListener("scroll", update);
    return () => {
      viewport.removeEventListener("resize", update);
      viewport.removeEventListener("scroll", update);
    };
  }, []);
}

/**
 * Long press (touch) or context menu (mouse) on an element; returns the handlers to spread. The click
 * that ends a long press is swallowed, so the row does not also open.
 */
export function useLongPress(onLongPress: () => void, delay = 480) {
  const state = useRef({ timer: 0, fired: false });
  const callback = useRef(onLongPress);
  callback.current = onLongPress;
  return {
    onPointerDown: (event: ReactPointerEvent) => {
      if (event.pointerType === "mouse") return;
      const current = state.current;
      current.fired = false;
      const x = event.clientX;
      const y = event.clientY;
      const cancel = (move: PointerEvent) => { if (Math.abs(move.clientX - x) + Math.abs(move.clientY - y) > 10) window.clearTimeout(current.timer); };
      const end = () => { window.clearTimeout(current.timer); window.removeEventListener("pointermove", cancel); };
      window.addEventListener("pointermove", cancel);
      window.addEventListener("pointerup", end, { once: true });
      window.addEventListener("pointercancel", end, { once: true });
      current.timer = window.setTimeout(() => {
        window.removeEventListener("pointermove", cancel);
        current.fired = true;
        navigator.vibrate?.(10);
        callback.current();
      }, delay);
    },
    onClickCapture: (event: ReactMouseEvent) => {
      if (!state.current.fired) return;
      state.current.fired = false;
      event.preventDefault();
      event.stopPropagation();
    },
    onContextMenu: (event: ReactMouseEvent) => {
      event.preventDefault();
      callback.current();
    },
  };
}
