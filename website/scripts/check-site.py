#!/usr/bin/env python3
"""静态站点检查：本地与 CI 共用，只用标准库，无需安装依赖。

检查 site/ 下的 HTML / CSS / JS：
- 本地引用（href / src / srcset / CSS url() / ES module import）全部指向存在的文件；
- 页内锚点（#id）都有对应元素；
- 外链只指向允许的主机，下载直链符合 Releases 的 Lagoon-<version>.apk 命名；
- 每个页面具备 lang、title、description、viewport；
- 体积预算：单文件与站点总量不超过上限，避免把大素材塞进仓库。
"""
from __future__ import annotations

import re
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "site"
ALLOWED_HOSTS = {"github.com", "schema.org", "lagoon.igng.net"}
APK_RE = re.compile(r"^https://github\.com/IGNGserver/opencode-lagoon/releases/download/v(\d+\.\d+\.\d+(?:-[a-z]+\.\d+)?)/Lagoon-(\1)\.apk$")
MAX_FILE = 400 * 1024
MAX_TOTAL = 1536 * 1024

errors: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


class Page(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.refs: list[tuple[str, str]] = []  # (attr, value)
        self.ids: set[str] = set()
        self.meta: dict[str, str] = {}
        self.lang = ""
        self.has_title = False
        self._in_title = False

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "html":
            self.lang = a.get("lang", "")
        if tag == "title":
            self._in_title = True
        if "id" in a:
            self.ids.add(a["id"])
        if tag == "meta" and "name" in a:
            self.meta[a["name"]] = a.get("content", "")
        for key in ("href", "src"):
            if a.get(key):
                self.refs.append((key, a[key]))
        if a.get("srcset"):
            for part in a["srcset"].split(","):
                self.refs.append(("srcset", part.strip().split(" ")[0]))
        if tag == "meta" and a.get("property") == "og:image":
            self.refs.append(("og:image", a.get("content", "")))

    def handle_endtag(self, tag):
        if tag == "title":
            self._in_title = False

    def handle_data(self, data):
        if self._in_title and data.strip():
            self.has_title = True


def resolve(base: Path, ref: str) -> Path:
    path = urlparse(ref).path
    if path.startswith("/"):
        return ROOT / path.lstrip("/")
    return (base.parent / path).resolve()


def check_local(base: Path, ref: str, where: str) -> None:
    target = resolve(base, ref)
    if target.is_dir():
        target = target / "index.html"
    if not target.is_file():
        err(f"{where}: 引用不存在 {ref}")


def check_html(path: Path) -> None:
    rel = path.relative_to(ROOT)
    page = Page()
    page.feed(path.read_text(encoding="utf-8"))
    if not page.lang:
        err(f"{rel}: <html> 缺少 lang")
    if not page.has_title:
        err(f"{rel}: 缺少 <title>")
    if "viewport" not in page.meta:
        err(f"{rel}: 缺少 viewport meta")
    if rel.name == "index.html" and not page.meta.get("description"):
        err(f"{rel}: 缺少 description meta")
    for attr, ref in page.refs:
        if ref.startswith("#"):
            if len(ref) > 1 and ref[1:] not in page.ids:
                err(f"{rel}: 锚点 {ref} 没有对应元素")
            continue
        url = urlparse(ref)
        if url.scheme in ("http", "https"):
            if url.hostname not in ALLOWED_HOSTS:
                err(f"{rel}: 外链主机不在允许列表 {ref}")
            if ref.endswith(".apk") and not APK_RE.match(ref):
                err(f"{rel}: 下载直链不符合 Releases 命名 {ref}")
            continue
        if url.scheme in ("mailto", "data"):
            continue
        check_local(path, ref, str(rel))


def check_css(path: Path) -> None:
    rel = path.relative_to(ROOT)
    for ref in re.findall(r"url\(\s*['\"]?([^'\")]+)['\"]?\s*\)", path.read_text(encoding="utf-8")):
        if ref.startswith(("data:", "#", "%23")):
            continue
        check_local(path, ref, str(rel))


def check_js(path: Path) -> None:
    rel = path.relative_to(ROOT)
    for ref in re.findall(r"^\s*import\s+(?:[^'\"]+\s+from\s+)?['\"]([^'\"]+)['\"]", path.read_text(encoding="utf-8"), re.M):
        if ref.startswith("."):
            check_local(path, ref, str(rel))
        else:
            err(f"{rel}: 不允许裸模块导入 {ref}（站点无构建步骤）")


def main() -> int:
    if not ROOT.is_dir():
        print(f"site directory missing: {ROOT}", file=sys.stderr)
        return 1
    files = [p for p in ROOT.rglob("*") if p.is_file()]
    total = 0
    for p in files:
        size = p.stat().st_size
        total += size
        if size > MAX_FILE:
            err(f"{p.relative_to(ROOT)}: 单文件 {size // 1024} KB 超过上限 {MAX_FILE // 1024} KB")
        if p.suffix == ".html":
            check_html(p)
        elif p.suffix == ".css":
            check_css(p)
        elif p.suffix == ".js":
            check_js(p)
    if total > MAX_TOTAL:
        err(f"站点总量 {total // 1024} KB 超过上限 {MAX_TOTAL // 1024} KB")
    if not (ROOT / "index.html").is_file():
        err("缺少 site/index.html")

    if errors:
        for e in errors:
            print(e, file=sys.stderr)
        return 1
    print(f"site check passed: {len(files)} files, {total // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
