# Photo Culling Assistant

Photo Culling Assistant is an Unraid-hosted Immich helper that matches RAW assets from one Immich account to final images from another. It runs entirely through the Immich API and never moves, renames, or deletes media files.

## Target Workflow

1. Connect to Immich with the required RAW-account and final-account API keys.
2. Read RAW-account assets from Immich.
3. Read edited-image-account assets from Immich.
4. Match edited images to RAW originals using filenames, capture time, and Immich metadata.
5. Auto-accept high-confidence matches, auto-reject low-confidence matches, and review the score band in between with Immich thumbnails.
6. Freeze and approve an immutable dry-run tag plan.
7. Apply that exact plan, reconciling `RAW Found` / `No RAW` / `duplicate` on final images and `Keeper` / `not used` on RAWs through the Immich API.
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
- Immich tag lookup/creation and tag application for `RAW Found`, `No RAW`, `duplicate`, `Keeper`, and `not used`.
- Optional shared access token for API actions with `PCA_ACCESS_TOKEN`.
- Dockerfile and starter Unraid template.
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

The `/config` directory must be mapped to persistent host storage. The included Unraid template maps it to `/mnt/user/appdata/photo-culling-assistant`; retain that mapping when updating or recreating the container. For a direct Docker run, use an equivalent volume mapping:

```sh
docker run -d --name photo-culling-assistant -p 8356:8356 \
  -v /mnt/user/appdata/photo-culling-assistant:/config \
  ghcr.io/YOUR_GITHUB_USER/photo-culling-assistant:latest
```

## Immutable Plans and Safe Retries

After every review is resolved, select **Approve dry-run plan**. This writes an immutable JSON plan and CSV manifest under `/config/tag-plans`. The plan contains an ID and SHA-256 fingerprint of the exact asset IDs, tag names, scores, and matching rationale that were reviewed.

The app will apply tags only when the request names the currently approved plan. Any new scan, review decision, undo, or tag-configuration change invalidates that approval; approve a new dry-run before applying.

Each apply operation is checkpointed under `/config/tag-operations` before and after every tag mutation. If the container or Immich is interrupted, selecting **Apply approved plan** again resumes incomplete steps for that same plan. A completed plan is idempotent and is not re-applied.

The configured decision tags are treated as app-managed states:

- RAW assets: `Keeper` and `not used` are mutually exclusive. The stale one is removed before the target state is applied.
- Final assets: `RAW Found` and `No RAW` are mutually exclusive. `duplicate` remains additive, but is removed from finals that are no longer duplicate candidates.

Use dedicated tags for this app. Do not configure these names to overlap with unrelated manual tagging workflows, because the app will reconcile assignments for scanned assets.

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

## Build

```powershell
gradle build
gradle packageServerZip
```

The web UI server zip is written to `build/distributions/photo-culling-assistant-server.zip`.

## Immich API Keys

This app requires two account-specific keys and does not read `IMMICH_API_KEY`.

- `RAW_IMMICH_API_KEY`: scans RAW assets for `RAW_USER_ID`, fetches RAW thumbnails, and applies RAW-side `Keeper` / `not used` tags.
- `FINAL_IMMICH_API_KEY`: scans final assets for `FINAL_USER_ID`, fetches final thumbnails, and applies final-side `RAW Found` / `No RAW` / `duplicate` tags.

`/api/status` reports whether both required keys are configured, but never returns secret values.

When upgrading an existing Unraid container, remove the obsolete `IMMICH_API_KEY` variable from its template. The application ignores it; only the two account-specific variables are used.

## Unraid Readiness

Before installing this as the real Immich cleanup helper, finish the checklist in [docs/unraid-preinstall-checklist.md](docs/unraid-preinstall-checklist.md).
