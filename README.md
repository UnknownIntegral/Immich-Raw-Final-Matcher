# Photo Culling Assistant

Photo Culling Assistant is an Unraid-hosted Immich helper that matches RAW assets from one Immich account to final images from another. It runs entirely through the Immich API and never moves, renames, or deletes media files.

## Target Workflow

1. Connect to Immich with either one shared API key or separate RAW and final-account API keys.
2. Read RAW-account assets from Immich.
3. Read edited-image-account assets from Immich.
4. Match edited images to RAW originals using filenames, capture time, and Immich metadata.
5. Auto-accept high-confidence matches, auto-reject low-confidence matches, and review the score band in between with Immich thumbnails.
6. Dry-run the tag plan.
7. Apply `RAW Found` / `No RAW` / `duplicate` to final images and `Keeper` / `not used` to RAWs through the Immich API.
8. Delete or archive `not used` RAWs from Immich after reviewing the tag in Immich.

The app must not move, rename, or delete files inside Immich-managed folders directly.

## Current Status

Ready now:

- Immich-only web UI server on port `8356` with progress reporting for lengthy scans.
- Review queue with side-by-side final and RAW thumbnails.
- Configurable auto-accept and auto-reject thresholds.
- Exact duplicate counts from Immich checksums, plus filename-only possible-duplicate counts.
- Completed scan sessions and review decisions persist under the app config directory and restore when the web UI reconnects.
- CSV dry-run manifest with proposed tags for both Immich accounts.
- Immich API client using `x-api-key`.
- Optional separate `RAW_IMMICH_API_KEY` and `FINAL_IMMICH_API_KEY` values for user-scoped Immich libraries.
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

Use `IMMICH_API_KEY` when one Immich API key can see both the RAW user's assets and the final-image user's assets, and can create/apply tags for both sides.

If Immich asset search is scoped to the key owner's library, set side-specific keys instead:

- `RAW_IMMICH_API_KEY`: used to scan RAW assets for `RAW_USER_ID` and apply RAW-side `Keeper` / `not used` tags.
- `FINAL_IMMICH_API_KEY`: used to scan final assets for `FINAL_USER_ID` and apply final-side `RAW Found` / `No RAW` / `duplicate` tags.

Both side-specific keys are optional. When either is blank, the app falls back to `IMMICH_API_KEY` for that side. `/api/status` reports whether each side has an effective key and which variable supplies it, but never returns the key values.

## Unraid Readiness

Before installing this as the real Immich cleanup helper, finish the checklist in [docs/unraid-preinstall-checklist.md](docs/unraid-preinstall-checklist.md).
