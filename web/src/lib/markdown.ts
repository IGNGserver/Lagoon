import MarkdownIt from "markdown-it";
import hljs from "highlight.js/lib/core";
import bash from "highlight.js/lib/languages/bash";
import c from "highlight.js/lib/languages/c";
import cpp from "highlight.js/lib/languages/cpp";
import css from "highlight.js/lib/languages/css";
import diff from "highlight.js/lib/languages/diff";
import go from "highlight.js/lib/languages/go";
import java from "highlight.js/lib/languages/java";
import javascript from "highlight.js/lib/languages/javascript";
import json from "highlight.js/lib/languages/json";
import kotlin from "highlight.js/lib/languages/kotlin";
import markdownLanguage from "highlight.js/lib/languages/markdown";
import python from "highlight.js/lib/languages/python";
import rust from "highlight.js/lib/languages/rust";
import sql from "highlight.js/lib/languages/sql";
import swift from "highlight.js/lib/languages/swift";
import typescript from "highlight.js/lib/languages/typescript";
import xml from "highlight.js/lib/languages/xml";
import yaml from "highlight.js/lib/languages/yaml";

const languages = { bash, c, cpp, css, diff, go, java, javascript, json, kotlin, markdown: markdownLanguage, python, rust, sql, swift, typescript, xml, yaml };
Object.entries(languages).forEach(([name, language]) => hljs.registerLanguage(name, language));
hljs.registerAliases(["sh", "shell", "zsh", "console"], { languageName: "bash" });
hljs.registerAliases(["js", "jsx", "mjs"], { languageName: "javascript" });
hljs.registerAliases(["ts", "tsx"], { languageName: "typescript" });
hljs.registerAliases(["kt", "kts", "gradle"], { languageName: "kotlin" });
hljs.registerAliases(["py"], { languageName: "python" });
hljs.registerAliases(["html", "svg", "vue"], { languageName: "xml" });
hljs.registerAliases(["yml"], { languageName: "yaml" });
hljs.registerAliases(["md"], { languageName: "markdown" });
hljs.registerAliases(["patch"], { languageName: "diff" });

const escapeHtml = (text: string) => text.replace(/[&<>"']/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[ch]!);

/** Highlighted HTML for [code]; plain escaped text when the language is unknown or the text is huge. */
export function highlight(code: string, language?: string): string {
  if (code.length > 200_000) return escapeHtml(code);
  const name = language?.toLowerCase();
  if (name && hljs.getLanguage(name)) {
    try {
      return hljs.highlight(code, { language: name, ignoreIllegals: true }).value;
    } catch {
      return escapeHtml(code);
    }
  }
  return escapeHtml(code);
}

/** Raw HTML in model output is shown as text, never interpreted. */
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: false,
  highlight: (code, language) => `<pre><code class="hljs">${highlight(code, language)}</code></pre>`,
});

const defaultLink = md.renderer.rules.link_open ?? ((tokens, index, options, _env, self) => self.renderToken(tokens, index, options));
md.renderer.rules.link_open = (tokens, index, options, env, self) => {
  const token = tokens[index];
  const href = token.attrGet("href") ?? "";
  if (!/^(https?:|mailto:)/i.test(href)) token.attrSet("href", "#");
  token.attrSet("target", "_blank");
  token.attrSet("rel", "noopener noreferrer");
  return defaultLink(tokens, index, options, env, self);
};
// Images in model output are links, not remote loads (and the CSP would block them anyway).
md.renderer.rules.image = (tokens, index) => {
  const token = tokens[index];
  const src = token.attrGet("src") ?? "";
  const alt = token.content || "图片";
  return /^https?:/i.test(src) ? `<a href="${escapeHtml(src)}" target="_blank" rel="noopener noreferrer">[图片] ${escapeHtml(alt)}</a>` : `[图片] ${escapeHtml(alt)}`;
};

const cache = new Map<string, string>();

export function renderMarkdown(text: string): string {
  const hit = cache.get(text);
  if (hit !== undefined) return hit;
  const html = md.render(text);
  if (text.length < 50_000) {
    cache.set(text, html);
    if (cache.size > 400) cache.delete(cache.keys().next().value!);
  }
  return html;
}

/** A language name for highlight.js from a file path. */
export function languageOf(path: string): string | undefined {
  const extension = path.substring(path.lastIndexOf(".") + 1).toLowerCase();
  return hljs.getLanguage(extension) ? extension : undefined;
}
