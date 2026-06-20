# Build, Publish, and Install on Unraid

This is the intended path for turning Photo Culling Assistant into an Unraid Community App. The Windows desktop app is legacy-only; the Unraid server and Immich tagging workflow are the supported direction.

## 1. Build the App as a Server

The code should keep the Unraid workflow separate from the legacy Windows desktop mover:

- `core`: matching, scoring, manifests, local visual matching.
- `server`: web UI and HTTP API.
- `immich`: Immich API client, optional read-only Postgres adapter.
- `desktop`: legacy Windows UI that reuses `core`, but is not part of the Unraid release path.

For Unraid, the server app should:

- Run a Web UI on a configurable port, default `8356`.
- Store config, cache, dry-run reports, and manifests in `/config`.
- Read Immich media through a read-only mount.
- Apply `RAW Found`, `No RAW`, `duplicate`, `Keeper`, and `not used` tags through the Immich API.
- Use read-only Postgres only as an optional performance accelerator.
- Never write to Immich Postgres.
- Never directly move/delete files inside Immich-managed folders.

## 2. Containerize It

Create a Dockerfile that builds and runs the server.

Example shape:

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/install/photo-culling-assistant-server/ /app/
EXPOSE 8356
ENV PCA_CONFIG_DIR=/config
ENTRYPOINT ["/app/bin/photo-culling-assistant-server"]
```

For releases, build a multi-arch image when possible:

```bash
docker buildx build \
  --platform linux/amd64 \
  -t ghcr.io/YOUR_GITHUB_USER/photo-culling-assistant:latest \
  --push .
```

Unraid servers are typically x86_64, so `linux/amd64` is the main target.

## 3. Publish the Image

Use one of:

- GitHub Container Registry: `ghcr.io/YOUR_GITHUB_USER/photo-culling-assistant:latest`
- Docker Hub: `YOUR_DOCKER_USER/photo-culling-assistant:latest`

GitHub Container Registry is a good fit if the source code lives on GitHub and releases are built with GitHub Actions.

Suggested release flow:

1. Push source to GitHub.
2. GitHub Actions builds the Docker image.
3. Image is pushed to GHCR.
4. Unraid template points to the GHCR image.

## 4. Create the Unraid Template

Unraid Community Apps install Docker containers through XML templates. Keep the template compatible with dockerMan-generated XML. Use `Container version="2"` and `Config` entries for paths, ports, and variables.

Example starter template:

```xml
<?xml version="1.0"?>
<Container version="2">
  <Name>photo-culling-assistant</Name>
  <Repository>ghcr.io/YOUR_GITHUB_USER/photo-culling-assistant:latest</Repository>
  <Registry>https://github.com/YOUR_GITHUB_USER/photo-culling-assistant/pkgs/container/photo-culling-assistant</Registry>
  <Network>bridge</Network>
  <Privileged>false</Privileged>
  <Support>https://github.com/YOUR_GITHUB_USER/photo-culling-assistant/issues</Support>
  <Project>https://github.com/YOUR_GITHUB_USER/photo-culling-assistant</Project>
  <Overview>Match RAW assets from one Immich user to final images from another Immich user, then tag finals as RAW Found/No RAW/duplicate and RAWs as Keeper/not used for review inside Immich.</Overview>
  <Category>MediaApp:Photos Productivity: Tools:</Category>
  <WebUI>http://[IP]:[PORT:8356]</WebUI>
  <TemplateURL>https://raw.githubusercontent.com/YOUR_GITHUB_USER/unraid-templates/main/photo-culling-assistant.xml</TemplateURL>
  <Icon>https://raw.githubusercontent.com/YOUR_GITHUB_USER/photo-culling-assistant/main/assets/icon.png</Icon>
  <Config Name="WebUI" Target="8356" Default="8356" Mode="tcp" Description="Web UI port" Type="Port" Display="always" Required="true" Mask="false"/>
  <Config Name="Config" Target="/config" Default="/mnt/user/appdata/photo-culling-assistant" Mode="rw" Description="App config, cache, manifests, and dry-run reports." Type="Path" Display="always" Required="true" Mask="false"/>
  <Config Name="Immich Media" Target="/immich-media" Default="/mnt/user/immich" Mode="ro" Description="Read-only mount of Immich media files for local preview/hash reads." Type="Path" Display="always" Required="true" Mask="false"/>
  <Config Name="Immich URL" Target="IMMICH_URL" Default="http://immich-server:2283" Mode="" Description="Immich server URL reachable from this container." Type="Variable" Display="always" Required="true" Mask="false"/>
  <Config Name="Immich API Key" Target="IMMICH_API_KEY" Default="" Mode="" Description="Immich API key. Prefer an admin key so both RAW and final users can be discovered." Type="Variable" Display="always" Required="true" Mask="true"/>
  <Config Name="RAW User ID" Target="RAW_USER_ID" Default="" Mode="" Description="Immich user ID that owns the RAW assets." Type="Variable" Display="always" Required="true" Mask="false"/>
  <Config Name="Final User ID" Target="FINAL_USER_ID" Default="" Mode="" Description="Immich user ID that owns the final JPG/JPEG/PNG assets." Type="Variable" Display="always" Required="true" Mask="false"/>
  <Config Name="Keeper Tag" Target="PCA_KEEPER_TAG" Default="Keeper" Mode="" Description="Tag to apply to RAW assets that have accepted final-image matches." Type="Variable" Display="advanced" Required="true" Mask="false"/>
  <Config Name="Unused RAW Tag" Target="PCA_UNUSED_TAG" Default="not used" Mode="" Description="Tag to apply to RAW assets without accepted final-image matches." Type="Variable" Display="advanced" Required="true" Mask="false"/>
  <Config Name="RAW Found Final Tag" Target="PCA_RAW_FOUND_TAG" Default="RAW Found" Mode="" Description="Tag to apply to final images with accepted RAW matches." Type="Variable" Display="advanced" Required="true" Mask="false"/>
  <Config Name="No RAW Final Tag" Target="PCA_NO_RAW_TAG" Default="No RAW" Mode="" Description="Tag to apply to final images where no RAW match can be found." Type="Variable" Display="advanced" Required="true" Mask="false"/>
  <Config Name="Duplicate Final Tag" Target="PCA_DUPLICATE_TAG" Default="duplicate" Mode="" Description="Tag to apply only to the lower-file-size final image when duplicate final filenames are found." Type="Variable" Display="advanced" Required="true" Mask="false"/>
  <Config Name="Dry Run Default" Target="DRY_RUN_DEFAULT" Default="true" Mode="" Description="Start in dry-run mode. Recommended." Type="Variable" Display="advanced" Required="true" Mask="false">true</Config>
</Container>
```

For optional read-only Postgres integration, add advanced variables later:

- `IMMICH_DB_HOST`
- `IMMICH_DB_PORT`
- `IMMICH_DB_NAME`
- `IMMICH_DB_USER`
- `IMMICH_DB_PASSWORD`
- `IMMICH_DB_SSL_MODE`

The app should still work without database access.

## 5. Test Locally on Unraid Before Community Apps

Fastest first install:

1. Finish the checklist in `docs/unraid-preinstall-checklist.md`.
2. Build/push the Docker image.
3. In Unraid, go to Docker > Add Container.
4. Set Repository to the image, for example `ghcr.io/YOUR_GITHUB_USER/photo-culling-assistant:latest`.
5. Add the same paths, port, and variables from the template.
6. Start it.
7. Open `http://tower:8356`.
8. Run scan in dry-run mode on a small test dataset.
9. Confirm proposed final-image and RAW tags before applying anything.

This proves the container works before publishing to Community Applications.

## 6. Install From a Template Repo

Create a public GitHub repo such as:

```text
YOUR_GITHUB_USER/unraid-templates
  photo-culling-assistant.xml
  ca_profile.xml
```

Then use Unraid's Docker template workflow to install from that XML, or add the template repository in Community Applications if your CA version exposes private template repositories.

The `TemplateURL` must be a raw GitHub URL to the XML file.

## 7. Publish to Community Applications

There are two common options:

1. Maintain your own template repository and request indexing by Community Applications.
2. Submit a pull request to a community template repository that accepts third-party templates.

The selfhosters template repository README currently says they prefer maintainers moving apps to their own template repos, and that pull requests may still be reviewed. Long-term, owning the template repo is cleaner.

Before requesting CA listing:

- Create a support thread or GitHub Issues link.
- Add a project URL.
- Add an icon served over HTTPS.
- Include a clear overview and requirements.
- Keep the app in dry-run mode by default.
- Explain that Immich deletion should happen through Immich after `not used` tagging.

## 8. Upgrade Flow

When a new version is released:

1. Push a new Docker image tag, for example `v0.2.0`.
2. Also update `latest` if desired.
3. Update the template only when ports, variables, or paths change.
4. Unraid users update the container from the Docker tab.

## 9. First Implementation Milestone

The first useful Unraid version should do only this:

- Connect to Immich API.
- Read RAW-user and final-user asset metadata.
- Match and score assets.
- Show review UI.
- Generate dry-run manifest.
- Apply `RAW Found`, `No RAW`, `duplicate`, `Keeper`, and `not used` tags through Immich API.

No file moves. No direct database writes. No direct deletes.
