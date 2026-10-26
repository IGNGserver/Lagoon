import { describe, expect, it } from "vitest";
import type { Message, MessagePart } from "@/api/types";
import { applyOps, mergeWindow, prependOlder } from "./ops";

const part = (id: string, text: string): MessagePart => ({
  id, type: "text", text, tool: "", title: "", status: "", input: "", output: "", path: "", error: "", patch: "",
  files: [], mime: "", attachments: [], target: null, background: false,
});
const message = (id: string, created: number, text = id): Message => ({
  id, role: "assistant", created, parts: [part(`${id}:text:0`, text)], model: null, notice: null, queued: false, type: "assistant",
});

describe("applyOps", () => {
  it("appends streamed text to the addressed part", () => {
    const next = applyOps([message("a", 1, "Hel")], [{ op: "append", messageId: "a", index: 0, field: "text", text: "lo" }]);
    expect(next[0].parts[0].text).toBe("Hello");
  });

  it("puts, replaces parts and deletes", () => {
    const start = [message("a", 1), message("b", 2)];
    const next = applyOps(start, [
      { op: "del", id: "a" },
      { op: "put", message: message("c", 3) },
      { op: "part", messageId: "b", index: 0, part: part("b:text:0", "changed") },
    ]);
    expect(next.map((it) => it.id)).toEqual(["b", "c"]);
    expect(next[0].parts[0].text).toBe("changed");
    expect(start[1].parts[0].text).toBe("b");
  });

  it("follows an explicit order", () => {
    const next = applyOps([message("in", 1), message("a", 2)], [], ["a", "in"]);
    expect(next.map((it) => it.id)).toEqual(["a", "in"]);
  });
});

describe("windows", () => {
  it("keeps older pages in front of a fresh window", () => {
    const loaded = [message("old", 1), message("x", 5)];
    expect(mergeWindow(loaded, [message("x", 5, "new"), message("y", 6)]).map((it) => it.id)).toEqual(["old", "x", "y"]);
    expect(prependOlder([message("x", 5)], [message("w", 4), message("x", 5)]).map((it) => it.id)).toEqual(["w", "x"]);
  });
});
