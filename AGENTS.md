# Lagoon repository

Collaboration: collaborative
Validation: `ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`; website changes also run `node website/scripts/build-site.mjs && python3 website/scripts/check-site.py website/dist` (set `GITHUB_TOKEN` to avoid the anonymous API limit).
Release: use `.github/workflows/release.yml`. If the user does not specify a release type, publish a pre-release. Every release tag must include a Chinese update note at `docs/releases/<tag>.md`; see `docs/RELEASE_POLICY.md`.

Lagoon is a native Android coding agent harness client, launching with OpenCode 2.x server support and architected for multiple harnesses. Keep server data authoritative and keep credentials out of source control. Local foreground monitoring notifies on task completion and attention requests.

## Website

The official site lives in `website/` — a zero-dependency static site under `website/site/`.
`website/scripts/build-site.mjs` reads the latest stable and prerelease from GitHub Releases, injects
the version/date/APK name/size/download links, and writes the deployable `website/dist/`;
`website/scripts/check-site.py` validates local references, anchors, external hosts and the size budget.
The site is deployed to the Cloudflare Pages project `lagoon-website` at <https://lagoon.igng.net/> by
`.github/workflows/website.yml` on `main` and on a `release-published` repository dispatch. The previous
standalone `lagoon-website` repository is archived.

- Do not commit `website/dist/`; it is generated. Do not add npm dependencies for the site.
- Site version/download data is injected at deploy time; content changes (visual language, feature copy)
  still need a manual review and the browser checks in `website/docs/SITE.md`.
- Deployment needs repository secrets `CLOUDFLARE_API_TOKEN` (Pages:Edit) and `CLOUDFLARE_ACCOUNT_ID`.
  Rollback and domain facts are in `website/docs/HOSTING.md`.
