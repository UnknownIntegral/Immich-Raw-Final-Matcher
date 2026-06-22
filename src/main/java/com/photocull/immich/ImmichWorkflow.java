package com.photocull.immich;

import com.photocull.matcher.MatchEngine;
import com.photocull.matcher.PhotoFile;
import com.photocull.server.ImmutableTagPlan;
import com.photocull.server.PlanApplyOperation;
import com.photocull.server.ScanSession;

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
        ImmutableTagPlan plan = ImmutableTagPlan.fromSession(session, config);
        PlanApplyOperation operation = PlanApplyOperation.create(plan);
        applyTags(plan, operation, ignored -> { });
        Path manifest = new ImmichTagManifestWriter().writeCsv(
                session.tagPlan(config.keeperTag(), config.unusedTag()),
                session.finalTagPlan(config.rawFoundTag(), config.noRawTag(), config.duplicateTag()), configDir);
        return result(plan, operation, manifest);
    }

    /**
     * Applies only the supplied immutable snapshot. The checkpoint is called
     * before and after every external mutation, which lets callers safely
     * resume an interrupted apply operation.
     */
    public ImmichTagApplyResult applyTags(
            ImmutableTagPlan plan,
            PlanApplyOperation operation,
            Consumer<PlanApplyOperation> checkpoint
    ) throws IOException, InterruptedException {
        config.requireConfigured();
        if (!operation.planId().equals(plan.id()) || !operation.planFingerprint().equals(plan.fingerprint())) {
            throw new IllegalArgumentException("Apply operation does not belong to the approved tag plan.");
        }

        if (operation.isComplete()) {
            return result(plan, operation, plan.manifest());
        }
        operation.begin();
        checkpoint.accept(operation);
        for (PlanApplyOperation.Step step : operation.steps()) {
            if (step.state() == PlanApplyOperation.StepState.COMPLETE) {
                continue;
            }
            operation.start(step);
            checkpoint.accept(operation);
            try {
                int affected = execute(step);
                operation.complete(step, affected);
                checkpoint.accept(operation);
            } catch (IOException | InterruptedException | RuntimeException ex) {
                operation.fail(step, ex);
                checkpoint.accept(operation);
                throw ex;
            }
        }
        return result(plan, operation, plan.manifest());
    }

    private int execute(PlanApplyOperation.Step step) throws IOException, InterruptedException {
        if (step.assetIds().isEmpty()) {
            return 0;
        }
        ImmichApi client = ImmutableTagPlan.RAW.equals(step.account()) ? rawClient : finalClient;
        String side = ImmutableTagPlan.RAW.equals(step.account()) ? "RAW-side" : "Final-side";
        ImmichTag tag;
        if (step.mutation() == PlanApplyOperation.Mutation.ADD) {
            try {
                tag = client.ensureTag(step.tag());
            } catch (IOException ex) {
                throw tagFailure(side, step.tag(), ex);
            }
        } else {
            tag = existingTag(client, step.tag());
            if (tag == null) {
                return 0;
            }
        }

        int affected;
        try {
            affected = mutateInBatches(client, tag.id(), step.assetIds(), step.mutation());
        } catch (IOException ex) {
            throw tagFailure(side, step.tag(), ex);
        }
        if (affected != step.assetIds().size()) {
            throw new IOException("Immich " + side + " " + step.mutation().name().toLowerCase()
                    + " operation for tag '" + step.tag() + "' completed " + affected + " of "
                    + step.assetIds().size() + " requested assets.");
        }
        return affected;
    }

    private ImmichTag existingTag(ImmichApi client, String name) throws IOException, InterruptedException {
        for (ImmichTag tag : client.tags()) {
            if (tag.name().equalsIgnoreCase(name) || tag.value().equalsIgnoreCase(name)) {
                return tag;
            }
        }
        return null;
    }

    private int mutateInBatches(
            ImmichApi client,
            String tagId,
            List<String> assetIds,
            PlanApplyOperation.Mutation mutation
    ) throws IOException, InterruptedException {
        List<String> uniqueIds = new ArrayList<>(new LinkedHashSet<>(assetIds));
        int tagged = 0;
        int batchSize = 500;
        for (int start = 0; start < uniqueIds.size(); start += batchSize) {
            int end = Math.min(uniqueIds.size(), start + batchSize);
            List<String> batch = uniqueIds.subList(start, end);
            tagged += mutation == PlanApplyOperation.Mutation.ADD
                    ? client.tagAssets(tagId, batch)
                    : client.untagAssets(tagId, batch);
        }
        return tagged;
    }

    private IOException tagFailure(String side, String tagName, IOException cause) {
        return new IOException("Immich " + side + " tag operation failed for tag '" + tagName
                + "'. Check that the " + sideKeyHint(side)
                + " can manage tags on that side: " + cause.getMessage(), cause);
    }

    private ImmichTagApplyResult result(ImmutableTagPlan plan, PlanApplyOperation operation, Path manifest) {
        return new ImmichTagApplyResult(
                itemCount(plan.rawItems(), ImmutableTagPlan.KEEPER),
                itemCount(plan.rawItems(), ImmutableTagPlan.UNUSED),
                itemCount(plan.finalItems(), ImmutableTagPlan.RAW_FOUND),
                itemCount(plan.finalItems(), ImmutableTagPlan.NO_RAW),
                itemCount(plan.finalItems(), ImmutableTagPlan.DUPLICATE),
                operation.affectedAssets("add-raw-keeper"),
                operation.affectedAssets("add-raw-unused"),
                operation.affectedAssets("add-final-raw-found"),
                operation.affectedAssets("add-final-no-raw"),
                operation.affectedAssets("add-final-duplicate"),
                manifest
        );
    }

    private int itemCount(List<ImmutableTagPlan.PlanItem> items, String decision) {
        return (int) items.stream().filter(item -> decision.equals(item.decision()))
                .map(ImmutableTagPlan.PlanItem::assetId).distinct().count();
    }

    private String sideKeyHint(String side) {
        return side.equals("RAW-side")
                ? "RAW_IMMICH_API_KEY"
                : "FINAL_IMMICH_API_KEY";
    }
}
