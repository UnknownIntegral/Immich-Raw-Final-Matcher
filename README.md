# Photo Culling Assistant

Photo Culling Assistant is an Unraid-hosted Immich helper that matches RAW assets from one Immich account to final images from another. It runs entirely through the Immich API and never moves, renames, or deletes media files.

## Target Workflow

1. Connect to Immich with the required RAW-account and final-account API keys.
2. Read RAW-account assets from Immich.
3. Read edited-image-account assets from Immich.
4. Match edited images to RAW originals using filenames, capture time, and Immich metadata.
5. Auto-accept high-confidence matches, auto-reject low-confidence matches, and review the score band in between with Immich thumbnails.
6. Freeze and approve an immutable dry-run tag plan.
7. Apply that exact plan, reconciling `RAW Found` / `No RAW` / `duplicate` on final images and `Keeper` / `not used` / `Final not found` on RAWs through the Immich API.
8. Delete or archive `not used` RAWs from Immich after reviewing the tag in Immich.

The app must not move, rename, or delete files inside Immich-managed folders directly.

## Current Status

Ready now:

- Immich-only web UI server on port `8356` with progress reporting for lengthy scans.
- Review queue with side-by-side final and RAW thumbnails.
- Review queue retains up to five scored RAW candidates and lets the reviewer select the intended RAW before accepting.
- Configurable auto-accept and auto-reject thresholds.
- Exact duplicate counts from Immich checksums, plus filename-only possible-duplicate counts.
- Completed scan sessions and review decisions persist under the app config directory and restore when the web UI reconnects.
- **Clear saved review data** permanently removes the local scan session, decision history, and saved tag-plan records after confirmation; it never deletes Immich media or changes Immich tags.
- Immutable, fingerprinted CSV/JSON dry-run plans with resumable tag-apply operations.
- Append-only, fsynced decision history for scans, automatic outcomes, manual review decisions, undo actions, plan approvals, and tag applications.
- Immich API client using `x-api-key`.
- Required separate `RAW_IMMICH_API_KEY` and `FINAL_IMMICH_API_KEY` values; no shared or admin Immich key is accepted.
- Immich image asset discovery by RAW owner ID and edited-image owner ID.
- Match/tag plans that preserve Immich asset IDs.
- Immich tag lookup/creation and tag application for `RAW Found`, `No RAW`, `duplicate`, `Keeper`, `not used`, and `Final not found`.
- Configured Immich Album membership for each app-managed decision state; Album actions are frozen and resumed with the same approved plan as tag actions.
- Deterministic date-and-sequence filename plans such as `2026-06-22-000001.jpg` / `2026-06-22-000001.cr3`, including unmatched finals, recorded in the immutable plan and manifest.
- Separate RAW-key and final-key permission cards that test asset read, tags, Albums, and cleanup through temporary app-scoped probes without exposing either key.
- Optional shared access token for API actions with `PCA_ACCESS_TOKEN`.
- Dockerfile and starter Unraid template.
- GitHub Actions publishing of a Linux amd64 image to `ghcr.io/unknownintegral/photo-culling-assistant:latest`.
- Lightweight automated tests for JSON parsing, Immich asset mapping, and asset-ID tag plans.

Not ready yet for the final Immich workflow:

- Production Unraid template values and published container image.
- Smoke testing against a real Immich instance.
- Stronger authentication if this is ever exposed beyond a trusted LAN.
- Broader automated tests for API failure handling, duplicate/conflicting matches, and tag application safety.
- Smoke testing against the target Immich version's thumbnail API.

## Matching Signals

- Exact filename stem, such as `IMG_1234.CR3` and `IMG_1234.jpg`.
- Shared trailing image number, such as `IMG_1234` and `Wedding_1234`.
- Best-effort EXIF/TIFF capture time from JPEG and many TIFF-like RAW formats.
- Camera make/model when available.
- Weak filename similarity for renamed exports.
- Exact duplicate finals use matching Immich checksums; filename-only collisions are reported as possible duplicates and are not tagged.
- Ambiguous final images with multiple strong RAW candidates are forced into review.

All matching runs in the app using metadata returned by Immich. The browser receives thumbnails only through the app's authenticated proxy; Immich API keys never reach the browser.

If the container restarts during a scan, the active HTTP scan cannot resume from its previous request cursor; it is marked as interrupted. The most recent completed session and its review decisions are retained.

## Reviewing in Chunks

Each accept, reject, and undo decision is written immediately to `/config/scan-session.json`. You can stop reviewing and restart the container at any time; when it starts again, open the web UI and continue from the remaining review queue. Do not start a new scan unless you intentionally want to replace the saved session.

The `/config` directory must be mapped to persistent host storage. The included Unraid template maps it to `/mnt/user/appdata/photo-culling-assistant`; retain that mapping when updating or recreating the container. The container writes structured JSON logs to Docker stdout/stderr (visible in Unraid) and mirrors them to `/config/logs/photo-culling-assistant-YYYY-MM-DD.log`; files older than seven days are removed. Logs cover startup/configuration, HTTP requests, scans, persistence/recovery, permission probes, Immich requests/retries, plan application, and periodic health. API keys, access tokens, request bodies, and thumbnail data are not logged. Set `PCA_LOG_LEVEL` to `DEBUG`, `INFO` (default), `WARN`, or `ERROR`; use `DEBUG` temporarily when diagnosing a problem because it includes successful Immich request timings and scan progress. For a direct Docker run, use an equivalent volume mapping:

```sh
docker run -d --name photo-culling-assistant -p 8356:8356 \
  -v /mnt/user/appdata/photo-culling-assistant:/config \
  ghcr.io/YOUR_GITHUB_USER/photo-culling-assistant:latest
```

## Immutable Plans and Safe Retries

After every review is resolved, select **Approve dry-run plan**. This writes an immutable JSON plan and CSV manifest under `/config/tag-plans`. The plan contains an ID and SHA-256 fingerprint of the exact asset IDs, tag names, scores, and matching rationale that were reviewed.

The app will apply tags only when the request names the currently approved plan. Any new scan, review decision, undo, or tag-configuration change invalidates that approval; approve a new dry-run before applying.

Each apply operation is checkpointed under `/config/tag-operations` before and after every tag mutation. If the container or Immich is interrupted, selecting **Apply approved plan** again resumes incomplete steps for that same plan. Selecting it after a completed operation starts a fresh refresh: it removes the configured PCA decision tags from all assets in the current plan, then reapplies the reviewed tag plan. PCA-managed Albums are reconciled by diff instead of clear-and-repopulate: existing correct members are left alone, missing tagged assets are added, and stale members are removed only when they are no longer part of that Album's current decision set.

The configured decision tags are treated as app-managed states:

- RAW assets: `Keeper`, `not used`, and `Final not found` are mutually exclusive. `not used` means a final image exists on the same capture date, but that RAW has no accepted final match. `Final not found` means there are no final images on the RAW's capture date.
- Final assets: `RAW Found`, `No RAW`, and `duplicate` are refreshed on every apply. All configured PCA decision tags are removed from the plan's assets before the reviewed tags are applied.

Use dedicated tags for this app. Do not configure these names to overlap with unrelated manual tagging workflows, because the app will reconcile assignments for scanned assets.

## Albums, filename plans, and API permissions

Each approved plan also adds its assets to these default Immich Albums. Override the names with environment variables if desired:

- `PCA_KEEPER_ALBUM` (default `PCA - Keeper RAWs`)
- `PCA_UNUSED_ALBUM` (default `PCA - Unused RAWs`)
- `PCA_FINAL_NOT_FOUND_ALBUM` (default `PCA - Final Not Found RAWs`)
- `PCA_RAW_FOUND_ALBUM` (default `PCA - Finished`)
- `PCA_NO_RAW_ALBUM` (default `PCA - No RAW`)
- `PCA_DUPLICATE_ALBUM` (default `PCA - Duplicates`)

Album membership is reconciled only for the configured PCA decision Albums above. The app does not touch unrelated personal Albums; do not point these settings at a personal Album unless you want PCA to manage membership for that decision state.

The separate **Create final lens Albums** action creates final-account Albums grouped by the final image EXIF lens model. It does not create tags or touch the RAW account. Lens Albums are additive: existing members are left alone and missing scanned final assets are added. Album names use `PCA_LENS_ALBUM_PREFIX` (default `PCA - Lens - `) followed by the lens model.

The plan assigns every final image a six-digit sequence per date (`YYYY-MM-DD-000001.ext`). A matched RAW shares the same basename with its native extension (for example, `2026-06-22-000001.jpg` and `2026-06-22-000001.CR3`); unmatched RAWs receive a free sequence for their own date. The plan and CSV manifest make the intended downloaded-library names reviewable.

**Important:** Immich's current public API exposes `originalFileName` for reading but not for safe updating. Therefore this app does not rename the displayed `IMG_2535` value, its database row, or the underlying Immich-managed file. The permission panel marks displayed-filename update as **Unsupported** rather than risking the database or storage paths. If Immich later adds an official filename-update API, the existing immutable filename plan can become its input safely.

After a scan, select **Test both API keys** to run each key's independent capability checks. The write checks temporarily create and remove an app-scoped tag and Album on one asset for the corresponding account. API-key values are never sent to the browser or written to status output.

## Decision History and Alternate RAWs

The **Decision history** tab shows the newest durable events first and can be filtered by a final or RAW Immich asset ID. Events are appended to `/config/history/decision-events.jsonl` and flushed to disk on each write. This file is sensitive: it includes asset IDs, paths, match scores, and review reasoning, so keep the `/config` share private.

When several RAWs are plausible, the review queue shows up to five scored candidates. Select a candidate with the **Use this RAW** button (or keys `1` through `5`), then accept it. The selected candidate, its score, and the prior suggested RAW are preserved in the decision history.

## Run

Unraid/web UI server:

```powershell
gradle run
```

or explicitly:

```powershell
gradle runServer
```

Then open `http://localhost:8356`.

## Unraid Docker Template and Updates

The Unraid template is at `unraid/photo-culling-assistant.xml` and installs the image
`ghcr.io/unknownintegral/photo-culling-assistant:latest`. After the first GitHub Actions
workflow run, make the package **public** in GitHub's package settings so Unraid can pull
it without registry credentials. Install the template once, retain the `/config` mapping,
then use Unraid's Docker update control for future releases.

## Build

```powershell
gradle build
gradle packageServerZip
```

The web UI server zip is written to `build/distributions/photo-culling-assistant-server.zip`.

## Immich API Keys

This app requires two account-specific keys and does not read `IMMICH_API_KEY`.

- `RAW_IMMICH_API_KEY`: scans RAW assets for `RAW_USER_ID`, fetches RAW thumbnails, and applies RAW-side `Keeper` / `not used` / `Final not found` tags.
- `FINAL_IMMICH_API_KEY`: scans final assets for `FINAL_USER_ID`, fetches final thumbnails, and applies final-side `RAW Found` / `No RAW` / `duplicate` tags.

`/api/status` reports whether both required keys are configured, but never returns secret values.

## Immich Reliability Controls

Tag and Album mutations use batches of 100 assets by default, wait up to 180 seconds for each Immich response, and retry safe GET, PUT, and DELETE requests up to three times after a timeout, rate-limit response, or server error. Every fully acknowledged batch is checkpointed, so retrying a failed apply resumes after the completed batches.

Advanced Unraid settings can override these defaults:

- `PCA_IMMICH_REQUEST_TIMEOUT_SECONDS` — response timeout, 10–600 seconds (default `180`).
- `PCA_IMMICH_MUTATION_BATCH_SIZE` — assets per tag or Album mutation, 1–500 (default `100`).
- `PCA_IMMICH_REQUEST_RETRY_ATTEMPTS` — total safe-request attempts, 1–5 (default `3`).

POST requests are deliberately not retried automatically because creation requests are not generally idempotent.

When upgrading an existing Unraid container, remove the obsolete `IMMICH_API_KEY` variable from its template. The application ignores it; only the two account-specific variables are used.

## Unraid Readiness

Before installing this as the real Immich cleanup helper, finish the checklist in [docs/unraid-preinstall-checklist.md](docs/unraid-preinstall-checklist.md).
