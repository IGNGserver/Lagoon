import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import type { Message } from "@/api/types";
import { build, chunks, details, type TranscriptRow } from "./rows";

interface ContractCase {
  name: string;
  working: boolean;
  unverified?: boolean;
  revertMessageId: string;
  details: string;
  expanded: string[];
  messages: Message[];
  rows: Omit<TranscriptRow, "source">[];
}

const contract = JSON.parse(readFileSync(fileURLToPath(new URL("../../../contract/transcript-rows.json", import.meta.url)), "utf8")) as { cases: ContractCase[] };

describe("TranscriptRows contract (shared with the Kotlin original)", () => {
  it.each(contract.cases.map((c) => [c.name, c] as const))("%s", (_, c) => {
    const revert = c.revertMessageId || null;
    const rows = c.details ? details(c.messages, c.details, c.working, revert, c.unverified) : build(c.messages, new Set(c.expanded), c.working, revert, c.unverified);
    expect(rows.map(({ source: _source, ...rest }) => rest)).toEqual(c.rows);
  });
});

describe("chunks", () => {
  it("splits at 80 lines and never inside a surrogate pair", () => {
    const lines = Array.from({ length: 100 }, (_, i) => `line ${i}`).join("\n");
    expect(chunks(lines)).toHaveLength(2);
    expect(chunks(lines).join("")).toBe(lines);
    const text = "a".repeat(4095) + "😀" + "b";
    const parts = chunks(text);
    expect(parts[0]).toHaveLength(4095);
    expect(parts.join("")).toBe(text);
  });
});
