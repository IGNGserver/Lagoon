/** Port of AttachmentPolicy (shared Attachments.kt) plus the browser side of importing a picked file. */
export const MAX_BYTES = 20 * 1024 * 1024;
const MAX_IMAGE_SIDE = 2000;
const MODEL_IMAGE_TYPES = new Set(["image/png", "image/jpeg", "image/gif", "image/webp"]);
const TEXT_TYPES = new Set(["application/json", "application/xml", "application/javascript", "application/x-sh", "application/x-yaml",
  "application/yaml", "application/toml", "application/sql", "image/svg+xml"]);
const TEXT_EXTENSIONS = new Set(["txt", "md", "markdown", "json", "jsonc", "yaml", "yml", "toml", "xml", "html", "htm", "css", "scss", "js", "jsx",
  "ts", "tsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "cc", "cpp", "h", "hpp", "sh", "bash", "sql", "swift", "php", "ini", "cfg",
  "conf", "properties", "env", "log", "csv", "tsv", "gradle", "svg", "diff", "patch"]);

export type AttachmentKind = "image" | "text";

export function kindOf(mime: string, name: string): AttachmentKind | null {
  const type = mime.split(";")[0].trim().toLowerCase();
  const extension = name.includes(".") ? name.substring(name.lastIndexOf(".") + 1).toLowerCase() : "";
  if (type === "image/svg+xml" || extension === "svg") return "text";
  if (type.startsWith("image/")) return "image";
  if (type.startsWith("text/") || TEXT_TYPES.has(type) || TEXT_EXTENSIONS.has(extension)) return "text";
  return null;
}

export interface PendingAttachment {
  id: string;
  name: string;
  /** Wire mime: text files travel as text/plain. */
  mime: string;
  size: number;
  uri: string;
  image: boolean;
}

function readDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error ?? new Error("读取失败"));
    reader.readAsDataURL(blob);
  });
}

/** Images the model cannot take, or larger than the server's default limit, are re-encoded before upload. */
async function normalizeImage(file: File): Promise<Blob> {
  // Animated GIFs go as they are.
  if (file.type === "image/gif") return file;
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, MAX_IMAGE_SIDE / Math.max(bitmap.width, bitmap.height));
  if (scale === 1 && MODEL_IMAGE_TYPES.has(file.type)) { bitmap.close(); return file; }
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext("2d")!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  const type = file.type === "image/png" ? "image/png" : "image/jpeg";
  return new Promise((resolve, reject) => canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error("图片转换失败"))), type, 0.9));
}

/** Imports one picked or pasted file, or explains why it cannot be sent. */
export async function importFile(file: File, pendingBytes: number): Promise<PendingAttachment | string> {
  const name = file.name || (file.type.startsWith("image/") ? "图片.png" : "附件");
  const kind = kindOf(file.type, name);
  if (!kind) return `模型读不到「${name}」这类文件，只能发送图片或文本文件`;
  if (file.size > MAX_BYTES) return `「${name}」超过 20 MB，无法发送`;
  try {
    if (kind === "image") {
      const blob = await normalizeImage(file);
      if (pendingBytes + blob.size > MAX_BYTES) return "附件合计超过 20 MB，请分几条消息发送";
      return { id: crypto.randomUUID(), name, mime: blob.type || file.type, size: blob.size, uri: await readDataUrl(blob), image: true };
    }
    if (pendingBytes + file.size > MAX_BYTES) return "附件合计超过 20 MB，请分几条消息发送";
    const text = new Blob([await file.arrayBuffer()], { type: "text/plain" });
    return { id: crypto.randomUUID(), name, mime: "text/plain", size: file.size, uri: await readDataUrl(text), image: false };
  } catch {
    return `无法读取「${name}」`;
  }
}
