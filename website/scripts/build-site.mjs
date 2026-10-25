import { cp, readFile, rm, writeFile } from 'node:fs/promises';

const REPO = 'IGNGserver/opencode-lagoon';
const siteRoot = new URL('../site/', import.meta.url);
const outRoot = new URL('../dist/', import.meta.url);

const headers = {
  Accept: 'application/vnd.github+json',
  'User-Agent': 'lagoon-website-build',
};
if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;

const response = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=30`, {
  headers,
  signal: AbortSignal.timeout(10000),
});
if (!response.ok) throw new Error(`GitHub API ${response.status}`);
const releases = await response.json();

const stable = releases.find((release) => !release.draft && !release.prerelease);
const preview = releases.find((release) => !release.draft && release.prerelease);
if (!stable) throw new Error('No stable release found');
if (!preview) throw new Error('No preview release found');

const stableVersion = stable.tag_name.replace(/^v/, '');
const previewVersion = preview.tag_name.replace(/^v/, '');
const previewSeries = previewVersion.replace(/\.\d+$/, '');
const stableDate = stable.published_at.slice(0, 10);
const previewDate = preview.published_at.slice(0, 10);
const apk = stable.assets.find((asset) => asset.name === `Lagoon-${stableVersion}.apk`);
if (!apk) throw new Error(`Stable release ${stable.tag_name} has no Lagoon-${stableVersion}.apk`);

const sizeMb = apk.size / 1048576;
const stableUrl = `https://github.com/${REPO}/releases/download/${stable.tag_name}/Lagoon-${stableVersion}.apk`;
const previewUrl = `https://github.com/${REPO}/releases/tag/${preview.tag_name}`;

await rm(outRoot, { recursive: true, force: true });
await cp(siteRoot, outRoot, { recursive: true });

let html = await readFile(new URL('index.html', outRoot), 'utf8');
const replaceAll = (from, to, label) => {
  if (!html.includes(from)) throw new Error(`Expected ${label} (${from}) not found in index.html`);
  html = html.split(from).join(to);
};

replaceAll('0.2.4', stableVersion, 'stable version');
replaceAll('0.2.5-rc.4', previewVersion, 'preview version');
replaceAll('0.2.5-rc', previewSeries, 'preview series');
replaceAll('2026-10-12', stableDate, 'stable date');
replaceAll('2026-10-14', previewDate, 'preview date');
replaceAll('APK · 10 MB', `APK · ${Math.round(sizeMb)} MB`, 'hero APK size');
replaceAll('<dd>10.0 MB</dd>', `<dd>${sizeMb.toFixed(1)} MB</dd>`, 'download card size');
replaceAll(
  'https://github.com/IGNGserver/lagoon-website',
  `https://github.com/${REPO}/tree/main/website`,
  'website source link',
);

if (!html.includes(stableUrl)) throw new Error(`Built page is missing the stable APK URL ${stableUrl}`);
if (!html.includes(previewUrl)) throw new Error(`Built page is missing the preview URL ${previewUrl}`);

await writeFile(new URL('index.html', outRoot), html);
console.log(`Built site for stable ${stable.tag_name} (${stableDate}) and preview ${preview.tag_name} (${previewDate}), APK ${sizeMb.toFixed(1)} MB`);
