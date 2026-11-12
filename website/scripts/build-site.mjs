import { cp, readFile, rm, writeFile } from 'node:fs/promises';

// 产品仓库与站点是同一个仓库（站点在 website/ 下），安装包与发布说明都在它的 Releases。
// 仓库改名后这里必须同步，否则 API 和下载链接只能靠 GitHub 的 301 兜着。
const REPO = 'IGNGserver/Lagoon';
const REPO_URL = `https://github.com/${REPO}`;
const siteRoot = new URL('../site/', import.meta.url);
const outRoot = new URL('../dist/', import.meta.url);

const headers = {
  Accept: 'application/vnd.github+json',
  'User-Agent': 'lagoon-website-build',
};
if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;

// 站点只认两种 tag：正式版 vX.Y.Z、预览版 vX.Y.Z-<channel>.N。
// 其它形态（nightly、临时 tag）不进官网，免得版本号串味或挑到没有 APK 的发布。
const STABLE_TAG = /^v\d+\.\d+\.\d+$/;
const PREVIEW_TAG = /^v\d+\.\d+\.\d+-[a-z]+\.\d+$/;

// Releases 接口按创建时间倒序，而“最新”要看 tag 的语义版本，所以必须翻完所有页：
// 预览版发得比正式版勤，正式版很容易被挤出第一页。
const PAGE_SIZE = 100;
async function fetchReleases() {
  const all = [];
  for (let page = 1; page <= 10; page += 1) {
    const response = await fetch(
      `https://api.github.com/repos/${REPO}/releases?per_page=${PAGE_SIZE}&page=${page}`,
      { headers, signal: AbortSignal.timeout(15000) },
    );
    if (!response.ok) throw new Error(`GitHub API ${response.status}（第 ${page} 页）`);
    const batch = await response.json();
    all.push(...batch);
    if (batch.length < PAGE_SIZE) break;
  }
  return all;
}

const releases = await fetchReleases();

// GitHub 的返回顺序不等于 semver 顺序，显式按 tag 取最大值，避免新预览被跳过。
const highest = (list) => [...list].sort((a, b) =>
  b.tag_name.localeCompare(a.tag_name, undefined, { numeric: true }))[0];
const pick = (pattern, prerelease) => highest(releases.filter((release) =>
  !release.draft && release.prerelease === prerelease && pattern.test(release.tag_name)));

const stable = pick(STABLE_TAG, false);
const preview = pick(PREVIEW_TAG, true);
if (!stable) throw new Error(`No stable release found in ${REPO}`);
if (!preview) throw new Error(`No preview release found in ${REPO}`);

const stableVersion = stable.tag_name.replace(/^v/, '');
const previewVersion = preview.tag_name.replace(/^v/, '');
const apkName = `Lagoon-${stableVersion}.apk`;
const apk = stable.assets.find((asset) => asset.name === apkName);
if (!apk) throw new Error(`Stable release ${stable.tag_name} has no ${apkName}`);

const sizeMb = apk.size / 1048576;

// index.html 里只留 __TOKEN__ 占位符，具体值在这里注入。模板缺一个 token、
// 或写了没定义的 token，构建都会失败——不会把未替换的占位符或旧版本号发上线。
const values = {
  REPO_URL,
  STABLE_VERSION: stableVersion,
  STABLE_DATE: stable.published_at.slice(0, 10),
  STABLE_APK: apkName,
  STABLE_APK_URL: `${REPO_URL}/releases/download/${stable.tag_name}/${apkName}`,
  STABLE_SIZE: `${sizeMb.toFixed(1)} MB`,
  STABLE_SIZE_SHORT: `${Math.round(sizeMb)} MB`,
  PREVIEW_VERSION: previewVersion,
  PREVIEW_DATE: preview.published_at.slice(0, 10),
  PREVIEW_SERIES: previewVersion.replace(/\.\d+$/, ''),
  PREVIEW_URL: `${REPO_URL}/releases/tag/${preview.tag_name}`,
};

await rm(outRoot, { recursive: true, force: true });
await cp(siteRoot, outRoot, { recursive: true });

let html = await readFile(new URL('index.html', outRoot), 'utf8');
for (const [name, value] of Object.entries(values)) {
  const token = `__${name}__`;
  if (!html.includes(token)) throw new Error(`index.html 缺少占位符 ${token}`);
  html = html.split(token).join(value);
}

const leftover = [...new Set(html.match(/__[A-Z][A-Z0-9_]*__/g) ?? [])];
if (leftover.length) throw new Error(`index.html 有未定义的占位符：${leftover.join(', ')}`);

await writeFile(new URL('index.html', outRoot), html);
console.log(`Built site for stable ${stable.tag_name} (${values.STABLE_DATE}) and preview ${preview.tag_name} (${values.PREVIEW_DATE}), APK ${values.STABLE_SIZE}`);
