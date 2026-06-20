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

    private final ImmichClient client;
    private final ImmichConfig config;

    public ImmichWorkflow(ImmichConfig config) {
        this.config = config;
        this.client = new ImmichClient(config);
    }

    public List<ImmichUser> users() throws IOException, InterruptedException {
        return client.users();
    }

    public ScanSession scan(int threshold, Consumer<String> progress) throws IOException, InterruptedException {
        config.requireConfigured();
        progress.accept("Reading RAW-account assets from Immich...");
        List<PhotoFile> raws = client.imageAssetsForOwner(config.rawUserId(), RAW_EXTENSIONS, progress)
                .stream()
                .map(ImmichAsset::toPhotoFile)
                .toList();

        progress.accept("Reading edited-image-account assets from Immich...");
        List<PhotoFile> finals = client.imageAssetsForOwner(config.finalUserId(), FINISHED_EXTENSIONS, progress)
                .stream()
                .map(ImmichAsset::toPhotoFile)
                .toList();

        progress.accept("Matching " + finals.size() + " edited images to " + raws.size() + " RAW assets...");
        return new ScanSession(raws, finals, new MatchEngine().match(raws, finals, threshold, progress), threshold);
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

        ImmichTag keeper = client.ensureTag(config.keeperTag());
        ImmichTag unused = client.ensureTag(config.unusedTag());
        ImmichTag rawFound = client.ensureTag(config.rawFoundTag());
        ImmichTag noRaw = client.ensureTag(config.noRawTag());
        ImmichTag duplicate = client.ensureTag(config.duplicateTag());
        int keeperTagged = tagInBatches(keeper.id(), keeperIds);
        int unusedTagged = tagInBatches(unused.id(), unusedIds);
        int rawFoundTagged = tagInBatches(rawFound.id(), rawFoundIds);
        int noRawTagged = tagInBatches(noRaw.id(), noRawIds);
        int duplicateTagged = tagInBatches(duplicate.id(), duplicateIds);
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

    private int tagInBatches(String tagId, List<String> assetIds) throws IOException, InterruptedException {
        List<String> uniqueIds = new ArrayList<>(new LinkedHashSet<>(assetIds));
        int tagged = 0;
        int batchSize = 500;
        for (int start = 0; start < uniqueIds.size(); start += batchSize) {
            int end = Math.min(uniqueIds.size(), start + batchSize);
            tagged += client.tagAssets(tagId, uniqueIds.subList(start, end));
        }
        return tagged;
    }
}
