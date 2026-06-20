# Unraid Preinstall Checklist

This is the practical gap list for using Photo Culling Assistant as an Immich RAW cleanup helper on Unraid.

## Current Reality

The container can run a local web UI, connect to Immich, read image assets, generate an asset-ID-based dry-run tag plan, and apply `RAW Found` / `No RAW` / `duplicate` tags to finals plus `Keeper` / `not used` tags to RAWs through the Immich API.

It still needs hardening and a real Immich smoke test before pointing it at the full library.

## Implemented

- Immich API client using `IMMICH_URL` plus either shared `IMMICH_API_KEY` or side-specific RAW/Final keys.
- Asset discovery that separates the RAW owner and edited-image owner.
- Match results and tag plans that include Immich asset IDs.
- `RAW Found`, `No RAW`, `duplicate`, `Keeper`, and `not used` tag lookup/creation through the Immich API.
- Tag application through the Immich API.
- CSV dry-run and tag-apply manifests with asset IDs.
- Optional shared access token for API actions with `PCA_ACCESS_TOKEN`.
- Keep all direct filesystem access read-only for Immich-managed folders.
- Remove or disable any file move/delete operation from the Unraid workflow.

## Must Finish Before Real Use

- Add broader tests for API failure handling, duplicate/conflicting matches, and tag application safety.
- Replace `YOUR_GITHUB_USER` placeholders in the Docker image name, registry links, support URL, project URL, and template URL.
- Build and publish a container image that Unraid can pull.
- Test on a small Immich album or temporary user pair before pointing it at the full library.
- Confirm the configured Immich API key setup can read both accounts and mutate tags. If one key cannot see both users' assets, create separate keys for the RAW and final Immich users.
- Set `PCA_ACCESS_TOKEN` in the Unraid template unless the app is isolated on a trusted network.

## Recommended Unraid Inputs

- `PCA_CONFIG_DIR=/config`
- `IMMICH_URL=http://immich-server:2283`
- `IMMICH_API_KEY=<shared key that can see both libraries, if available>`
- `RAW_IMMICH_API_KEY=<optional RAW user's API key when RAW assets need their own key>`
- `FINAL_IMMICH_API_KEY=<optional final user's API key when final assets need their own key>`
- `RAW_USER_ID=<Immich user ID for RAW assets>`
- `FINAL_USER_ID=<Immich user ID for edited images>`
- Read-only Immich media mount, only for local preview/hash reads.

`RAW_IMMICH_API_KEY` and `FINAL_IMMICH_API_KEY` fall back to `IMMICH_API_KEY` when blank. On Unraid, mask all three key variables. `/api/status` only reports whether the effective RAW and final keys are configured; it does not expose secret values.

## Safe First Install

1. Build and publish the Docker image.
2. Install from the Unraid template with Immich media mounted read-only.
3. Start with a small test dataset.
4. Run matching.
5. Review all low-confidence rows.
6. Write a dry-run manifest.
7. Confirm the tag plan has the expected final-image and RAW tags.
8. Apply tags through Immich only after the dry-run looks correct.
9. In Immich, filter the RAW account by the `not used` tag and delete there.

## Do Not Do

- Do not move RAWs out of Immich `upload`, `library`, `thumbs`, `encoded-video`, or `profile` folders through the filesystem.
- Do not delete Immich asset files directly from Unraid shares.
- Do not write to Immich Postgres.
- Do not rely on filenames alone for destructive cleanup.
