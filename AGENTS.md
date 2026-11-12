# Lagoon repository

Collaboration: collaborative
Validation: `ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`; website changes also run `node website/scripts/build-site.mjs && python3 website/scripts/check-site.py website/dist` (set `GITHUB_TOKEN` to avoid the anonymous API limit).
Release: use `.github/workflows/release.yml`. If the user does not specify a release type, publish a pre-release. Every release tag must include a Chinese update note at `docs/releases/<tag>.md`; see `docs/RELEASE_POLICY.md`.

Lagoon is a native Android coding agent harness client (OpenCode 2.x and DeepSeek Harness). Platform-neutral Kotlin lives in `shared/` and is compiled into the app. The v3 self-hosted hub and PWA web client were retired and removed; see `docs/HUB_EOL.md`. Keep server data authoritative and keep credentials out of source control. Local foreground monitoring notifies on task completion and attention requests.

## Website

The official site lives in `website/` — a zero-dependency static site under `website/site/`.
`website/scripts/build-site.mjs` pages through GitHub Releases (repo `IGNGserver/Lagoon`), picks the
highest stable and prerelease tag, and replaces the `__TOKEN__` placeholders in `website/site/index.html`
with the version/date/APK name/size/download links to write the deployable `website/dist/`;
`website/scripts/check-site.py` validates local references, anchors, external hosts and the size budget.
The site is deployed to the Cloudflare Pages project `lagoon-website` at <https://lagoon.igng.net/> by
`.github/workflows/website.yml` on `main` and on a `release-published` repository dispatch. The previous
standalone `lagoon-website` repository is archived.

- Do not commit `website/dist/`; it is generated. Do not add npm dependencies for the site.
- Site version/download data is injected at deploy time from `__TOKEN__` placeholders; keep the
  placeholders when editing copy (the build fails on a missing or unknown token) and still review the
  content-level copy, with the browser checks in `website/docs/SITE.md`.
- Deployment needs repository secrets `CLOUDFLARE_API_TOKEN` (Pages:Edit) and `CLOUDFLARE_ACCOUNT_ID`.
  Rollback and domain facts are in `website/docs/HOSTING.md`.
