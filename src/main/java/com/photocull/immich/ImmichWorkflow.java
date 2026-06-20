package com.photocull.immich;

import com.photocull.matcher.MatchEngine;
import com.photocull.matcher.PhotoFile;
import com.photocull.server.FinalTagPlanItem;
import com.photocull.server.ScanSession;
import com.photocull.server.TagPlanItem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class ImmichWorkflow {
    private static final Set<String> RAW_EXTENSIONS = Set.of(
            "cr2", "cr3", "arw", "dng", "nef", "nrw", "orf", "raf", "rw2", "pef", "srw", "x3f"
    );
    private static final Set<String> FINISHED_EXTENSIONS = Set.of("jpg", "jpeg", "png");

    private final ImmichApi usersClient;
    private final ImmichApi rawClient;
    private final ImmichApi finalClient;
    private final ImmichConfig config;

    public ImmichWorkflow(ImmichConfig config) {
        this(
                config,
                new ImmichClient(config, config.userLookupApiKey()),
                new ImmichClient(config, config.effectiveRawApiKey()),
                new ImmichClient(config, config.effectiveFinalApiKey())
        );
    }

    public ImmichWorkflow(ImmichConfig config, ImmichApi usersClient, ImmichApi rawClient, ImmichApi finalClient) {
        this.config = config;
        this.usersClient = usersClient;
        this.rawClient = rawClient;
        this.finalClient = finalClient;
    }

    public List<ImmichUser> users() throws IOException, InterruptedException {
        return usersClient.users();
    }

    public byte[] thumbnail(String assetId, boolean rawAsset) throws IOException, InterruptedException {
        config.requireConfigured();
        return (rawAsset ? rawClient : finalClient).thumbnail(assetId);
    }

    public ScanSession scan(int autoAcceptThreshold, int autoRejectThreshold, Consumer<String> progress) throws IOException, InterruptedException {
        config.requireConfigured();
        progress.accept("Reading RAW-account assets from Immich...");
        List<PhotoFile> raws = rawClient.imageAssetsForOwner(config.rawUserId(), RAW_EXTENSIONS, progress)
                .stream()
                .map(ImmichAsset::toPhotoFile)
                .toList();

        progress.accept("Reading edited-image-account assets from Immich...");
        List<PhotoFile> finals = finalClient.imageAssetsForOwner(config.finalUserId(), FINISHED_EXTENSIONS, progress)
                .stream()
                .map(ImmichAsset::toPhotoFile)
                .toList();

        progress.accept("Matching " + finals.size() + " edited images to " + raws.size() + " RAW assets...");
        return new ScanSession(raws, finals,
                new MatchEngine().match(raws, finals, autoAcceptThreshold, autoRejectThreshold, progress),
                autoAcceptThreshold, autoRejectThreshold);
    }

    public ScanSession scan(int threshold, Consumer<String> progress) throws IOException, InterruptedException {
        return scan(threshold, 0, progress);
    }

    public ImmichTagApplyResult applyTags(ScanSession session, Path configDir) throws IOException, InterruptedException {
        config.requireConfigured();
        if (session.rawReviewCount() > 0) {
            throw new IllegalStateException("Resolve RAW matches that still need review before applying Immich tags.");
        }

        List<TagPlanItem> rawPlan = session.tagPlan(config.keeperTag(), config.unusedTag());
        List<FinalTagPlanItem> finalPlan = session.finalTagPlan(config.rawFoundTag(), config.noRawTag(), config.duplicateTag());
        List<String> keeperIds = new ArrayList<>();
        List<String> unusedIds = new ArrayList<>();
        for (TagPlanItem item : rawPlan) {
            if (item.rawAssetId() == null || item.rawAssetId().isBlank()) {
                throw new IllegalStateException("Tag plan contains RAW rows without Immich asset IDs.");
            }
            if (item.tag().equals(config.keeperTag())) {
                keeperIds.add(item.rawAssetId());
            } else if (item.tag().equals(config.unusedTag())) {
                unusedIds.add(item.rawAssetId());
            }
        }

        List<String> rawFoundIds = new ArrayList<>();
        List<String> noRawIds = new ArrayList<>();
        List<String> duplicateIds = new ArrayList<>();
        for (FinalTagPlanItem item : finalPlan) {
            if (item.finalAssetId() == null || item.finalAssetId().isBlank()) {
                throw new IllegalStateException("Tag plan contains final-image rows without Immich asset IDs.");
            }
            if (item.tag().equals(config.rawFoundTag())) {
                rawFoundIds.add(item.finalAssetId());
            } else if (item.tag().equals(config.noRawTag())) {
                noRawIds.add(item.finalAssetId());
            } else if (item.tag().equals(config.duplicateTag())) {
                duplicateIds.add(item.finalAssetId());
            }
        }

        int keeperTagged = applyTag(rawClient, "RAW-side", config.keeperTag(), keeperIds);
        int unusedTagged = applyTag(rawClient, "RAW-side", config.unusedTag(), unusedIds);
        int rawFoundTagged = applyTag(finalClient, "Final-side", config.rawFoundTag(), rawFoundIds);
        int noRawTagged = applyTag(finalClient, "Final-side", config.noRawTag(), noRawIds);
        int duplicateTagged = applyTag(finalClient, "Final-side", config.duplicateTag(), duplicateIds);
        Path manifest = new ImmichTagManifestWriter().writeCsv(rawPlan, finalPlan, configDir);
        return new ImmichTagApplyResult(
                keeperIds.size(),
                unusedIds.size(),
                rawFoundIds.size(),
                noRawIds.size(),
                duplicateIds.size(),
                keeperTagged,
                unusedTagged,
                rawFoundTagged,
                noRawTagged,
                duplicateTagged,
                manifest
        );
    }

    private int applyTag(ImmichApi client, String side, String tagName, List<String> assetIds)
            throws IOException, InterruptedException {
        if (assetIds.isEmpty()) {
            return 0;
        }
        try {
            ImmichTag tag = client.ensureTag(tagName);
            return tagInBatches(client, tag.id(), assetIds);
        } catch (IOException ex) {
            throw new IOException("Immich " + side + " tag operation failed for tag '" + tagName
                    + "'. Check that the " + sideKeyHint(side)
                    + " can create tags and tag assets on that side: " + ex.getMessage(), ex);
        }
    }

    private int tagInBatches(ImmichApi client, String tagId, List<String> assetIds) throws IOException, InterruptedException {
        List<String> uniqueIds = new ArrayList<>(new LinkedHashSet<>(assetIds));
        int tagged = 0;
        int batchSize = 500;
        for (int start = 0; start < uniqueIds.size(); start += batchSize) {
            int end = Math.min(uniqueIds.size(), start + batchSize);
            tagged += client.tagAssets(tagId, uniqueIds.subList(start, end));
        }
        return tagged;
    }

    private String sideKeyHint(String side) {
        return side.equals("RAW-side")
                ? "RAW_IMMICH_API_KEY or fallback IMMICH_API_KEY"
                : "FINAL_IMMICH_API_KEY or fallback IMMICH_API_KEY";
    }
}
