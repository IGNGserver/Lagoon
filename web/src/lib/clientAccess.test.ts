import { describe, expect, it, vi } from "vitest";
import { importLink, keySummary, stripKeyFragment } from "./clientAccess";

const KEY = "lgk1.abcd2345.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

describe("import links", () => {
  it("put the key in the fragment of the hub address, like HubImportLink.build", () => {
    expect(importLink("https://hub.lan:8443", KEY)).toBe(`https://hub.lan:8443/#key=${KEY}`);
    expect(importLink("http://10.0.0.5:8080/", KEY)).toBe(`http://10.0.0.5:8080/#key=${KEY}`);
  });

  it("are removed from the address bar when opened in a browser", () => {
    const replaceState = vi.fn();
    expect(stripKeyFragment({ hash: `#key=${KEY}`, pathname: "/", search: "" }, { replaceState, state: { depth: 0 } })).toBe(true);
    expect(replaceState).toHaveBeenCalledWith({ depth: 0 }, "", "/");
    expect(stripKeyFragment({ hash: "#section", pathname: "/settings", search: "" }, { replaceState, state: null })).toBe(false);
    expect(replaceState).toHaveBeenCalledTimes(1);
  });
});

describe("key summaries", () => {
  const now = Date.UTC(2026, 9, 27, 12, 0);
  it("say when a key was never used", () => {
    expect(keySummary({ id: "a", label: "Pixel", created: Date.UTC(2026, 9, 27, 9, 5), lastUsed: 0, lastAddress: "" }, now)).toBe("创建于 今天 09:05 · 从未使用");
  });
  it("say when and from where it was last used", () => {
    expect(keySummary({ id: "a", label: "Pixel", created: Date.UTC(2025, 0, 2, 3, 4), lastUsed: Date.UTC(2026, 9, 20, 8, 30), lastAddress: "10.0.0.9" }, now))
      .toBe("创建于 2025年1月2日 · 最近导入 10月20日 08:30 · 来自 10.0.0.9");
  });
});
