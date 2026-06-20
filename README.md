# Photo Culling Assistant

Photo Culling Assistant is being focused on one target: an Unraid-hosted Immich helper that matches RAW assets from one Immich account to final images from another Immich account. It tags final-image assets as `RAW Found`, `No RAW`, or `duplicate`, and RAW assets as `Keeper` or `not used`, so cleanup happens inside Immich.

The old Windows desktop mover is now legacy-only. It still exists for reference and local experiments, but the supported direction is the Unraid web server and Immich tagging workflow.

## Target Workflow

1. Connect to Immich with an API key.
2. Read RAW-account assets from Immich.
3. Read edited-image-account assets from Immich.
4. Match edited images to RAW originals using filenames, capture time, metadata, and local visual hashes.
5. Review uncertain matches in the web UI.
6. Dry-run the tag plan.
7. Apply `RAW Found` / `No RAW` / `duplicate` to final images and `Keeper` / `not used` to RAWs through the Immich API.
8. Delete or archive `not used` RAWs from Immich after reviewing the tag in Immich.

The app must not move, rename, or delete files inside Immich-managed folders directly.

## Current Status

Ready now:

- Local web UI server on port `8356`.
- Filesystem folder scan for RAW and edited-image folders.
- Local matching and confidence scoring.
- Review UI for accepting or rejecting proposed matches.
- CSV dry-run manifest with proposed tags for both Immich accounts.
- Immich API client using `x-api-key`.
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
- Optional local media path mapping for visual hash reads from Unraid mounts.

## Matching Signals

- Exact filename stem, such as `IMG_1234.CR3` and `IMG_1234.jpg`.
- Shared trailing image number, such as `IMG_1234` and `Wedding_1234`.
- Best-effort EXIF/TIFF capture time from JPEG and many TIFF-like RAW formats.
- Camera make/model when available.
- Local-only visual similarity using perceptual hashes from finished images and best-effort embedded RAW JPEG previews.
- Weak filename similarity for renamed exports.
- Duplicate finals with the same filename stem; only lower-file-size copies receive the `duplicate` tag.
- Ambiguous final images with multiple strong RAW candidates are forced into review.

All matching and image recognition runs locally. The app should not upload images, hashes, or metadata to external services.

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

Legacy Windows desktop app:

```powershell
gradle runDesktop
```

## Build

```powershell
gradle build
gradle packageServerZip
```

The web UI server zip is written to `build/distributions/photo-culling-assistant-server.zip`.

Legacy Windows desktop zip:

```powershell
gradle packageDesktopZip
```

The legacy desktop zip is written to `build/distributions/photo-culling-assistant-desktop.zip`.

## Unraid Readiness

Before installing this as the real Immich cleanup helper, finish the checklist in [docs/unraid-preinstall-checklist.md](docs/unraid-preinstall-checklist.md).
