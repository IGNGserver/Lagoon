import { AlertCircle, Info } from "lucide-react";
import type { SessionStatus } from "@/api/types";
import { Spinner, cx } from "./ui";

/** One session indicator, in the official priority: attention › running › unread failure › unread result. */
export function StatusGlyph({ status, unseen }: { status: SessionStatus; unseen: boolean }) {
  switch (status) {
    case "RUNNING":
      return <Spinner size={16} />;
    case "WAITING_SERVER":
    case "UNKNOWN":
      return <Info size={18} className="text-text-2" />;
    case "WAITING_PERMISSION":
    case "WAITING_QUESTION":
      return <AlertCircle size={18} className="text-warning" />;
    case "FAILED":
      return <span className="block size-2 rounded-full bg-error" />;
    default:
      return <span className={cx("block rounded-full", unseen || status === "COMPLETED" ? "size-2 bg-primary" : "size-1.5 bg-text-3/50")} />;
  }
}

export function StatusDot({ tone }: { tone: "ok" | "busy" | "off" }) {
  return <span className={cx("inline-block size-2 shrink-0 rounded-full", tone === "ok" ? "bg-success" : tone === "busy" ? "bg-warning" : "bg-text-3")} />;
}
