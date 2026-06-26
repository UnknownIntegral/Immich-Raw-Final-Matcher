package com.photocull.immich;

import com.photocull.server.AppLog;
import com.photocull.matcher.MatchEngine;
import com.photocull.matcher.PhotoFile;
import com.photocull.server.ImmutableTagPlan;
import com.photocull.server.PlanApplyOperation;
import com.photocull.server.ScanSession;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class ImmichWorkflow {
    private static final Set<String> RAW_EXTENSIONS = Set.of(
            "cr2", "cr3", "arw", "dng", "nef", "nrw", "orf", "raf", "rw2", "pef", "srw", "tif", "x3f"
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
        long startedAt = System.nanoTime();
        AppLog.info("immich.scan_started", Map.of("autoAcceptThreshold", autoAcceptThreshold, "autoRejectThreshold", autoRejectThreshold));
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
        ScanSession session = new ScanSession(raws, finals,
                new MatchEngine().match(raws, finals, autoAcceptThreshold, autoRejectThreshold, progress),
                autoAcceptThreshold, autoRejectThreshold);
        AppLog.info("immich.scan_matched", Map.of("rawCount", raws.size(), "finalCount", finals.size(),
                "reviewCount", session.reviewCount(), "durationMillis", java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis()));
        return session;
    }

    public ScanSession scan(int threshold, Consumer<String> progress) throws IOException, InterruptedException {
        return scan(threshold, 0, progress);
    }

    /**
     * Checks the capabilities this application actually needs for each
     * account-specific key. The two write probes use a temporary tag/Album on
     * one scanned asset, then remove and delete them in a finally block.
     */
    public ImmichPermissionReport checkPermissions(ScanSession session) {
        config.requireConfigured();
        AppLog.info("immich.permission_check_started", Map.of("rawAssets", session.raws().size(), "finalAssets", session.finals().size()));
        return new ImmichPermissionReport(Instant.now(),
                checkAccount("RAW API key", rawClient, config.rawUserId(), RAW_EXTENSIONS, session.raws()),
                checkAccount("Final API key", finalClient, config.finalUserId(), FINISHED_EXTENSIONS, session.finals()));
    }

    private ImmichPermissionReport.Account checkAccount(
            String label,
            ImmichApi client,
            String ownerId,
            Set<String> extensions,
            List<PhotoFile> assets
    ) {
        List<ImmichPermissionReport.Check> checks = new ArrayList<>();
        PhotoFile probeAsset = assets.isEmpty() ? null : assets.get(0);
        checks.add(run("Asset scan", () -> client.imageAssetsForOwner(ownerId, extensions, ignored -> { })));
        if (probeAsset == null || probeAsset.immichAssetId() == null || probeAsset.immichAssetId().isBlank()) {
            checks.add(skipped("Thumbnail read", "Run a scan that finds at least one asset before testing this key."));
        } else {
            checks.add(run("Thumbnail read", () -> client.thumbnail(probeAsset.immichAssetId())));
        }
        checks.add(run("Tag read", client::tags));
        checks.add(run("Album read", client::albums));
        if (probeAsset == null || probeAsset.immichAssetId() == null || probeAsset.immichAssetId().isBlank()) {
            checks.add(skipped("Tag write", "A scanned test asset is required for the temporary probe."));
            checks.add(skipped("Album write", "A scanned test asset is required for the temporary probe."));
        } else {
            checks.add(run("Tag write", () -> probeTagWrite(client, probeAsset.immichAssetId())));
            checks.add(run("Album write", () -> probeAlbumWrite(client, probeAsset.immichAssetId())));
        }
        checks.add(new ImmichPermissionReport.Check("Displayed filename update", ImmichPermissionReport.State.UNSUPPORTED,
                "Immich's public API does not expose a writable originalFileName field; this app will not alter database rows or storage paths."));
        return new ImmichPermissionReport.Account(label, checks);
    }

    private void probeTagWrite(ImmichApi client, String assetId) throws IOException, InterruptedException {
        String name = "PCA permission probe " + UUID.randomUUID();
        ImmichTag tag = null;
        boolean attached = false;
        try {
            tag = client.ensureTag(name);
            int added = client.tagAssets(tag.id(), List.of(assetId));
            if (added != 1) {
                throw new IOException("The temporary tag probe did not attach to its test asset.");
            }
            attached = true;
        } finally {
            Exception cleanupFailure = null;
            if (tag != null && attached) {
                try {
                    int removed = client.untagAssets(tag.id(), List.of(assetId));
                    if (removed != 1) {
                        cleanupFailure = new IOException("The temporary tag probe could not be removed from its test asset.");
                    }
                } catch (IOException | InterruptedException ex) {
                    cleanupFailure = ex;
                }
            }
            if (tag != null) {
                try {
                    client.deleteTag(tag.id());
                } catch (IOException | InterruptedException ex) {
                    if (cleanupFailure == null) {
                        cleanupFailure = ex;
                    }
                }
            }
            if (cleanupFailure != null) {
                rethrowCleanupFailure(cleanupFailure);
            }
        }
    }

    private void probeAlbumWrite(ImmichApi client, String assetId) throws IOException, InterruptedException {
        String name = "PCA permission probe " + UUID.randomUUID();
        ImmichAlbum album = null;
        boolean attached = false;
        try {
            album = client.ensureAlbum(name);
            int added = client.addAssetsToAlbum(album.id(), List.of(assetId));
            if (added != 1) {
                throw new IOException("The temporary Album probe did not attach to its test asset.");
            }
            attached = true;
        } finally {
            Exception cleanupFailure = null;
            if (album != null && attached) {
                try {
                    int removed = client.removeAssetsFromAlbum(album.id(), List.of(assetId));
                    if (removed != 1) {
                        cleanupFailure = new IOException("The temporary Album probe could not be removed from its test asset.");
                    }
                } catch (IOException | InterruptedException ex) {
                    cleanupFailure = ex;
                }
            }
            if (album != null) {
                try {
                    client.deleteAlbum(album.id());
                } catch (IOException | InterruptedException ex) {
                    if (cleanupFailure == null) {
                        cleanupFailure = ex;
                    }
                }
            }
            if (cleanupFailure != null) {
                rethrowCleanupFailure(cleanupFailure);
            }
        }
    }

    private ImmichPermissionReport.Check run(String capability, ThrowingOperation operation) {
        try {
            operation.run();
            return new ImmichPermissionReport.Check(capability, ImmichPermissionReport.State.PASS, "Allowed");
        } catch (Exception ex) {
            return new ImmichPermissionReport.Check(capability, ImmichPermissionReport.State.FAIL, safeError(ex));
        }
    }

    private ImmichPermissionReport.Check skipped(String capability, String detail) {
        return new ImmichPermissionReport.Check(capability, ImmichPermissionReport.State.SKIPPED, detail);
    }

    private String safeError(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.replace(config.effectiveRawApiKey(), "[redacted]")
                .replace(config.effectiveFinalApiKey(), "[redacted]");
    }

    private void rethrowCleanupFailure(Exception failure) throws IOException, InterruptedException {
        if (failure instanceof InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (failure instanceof IOException io) {
            throw io;
        }
        throw new IOException(failure.getMessage(), failure);
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    public ImmichTagApplyResult applyTags(ScanSession session, Path configDir) throws IOException, InterruptedException {
        ImmutableTagPlan plan = ImmutableTagPlan.fromSession(session, config);
        PlanApplyOperation operation = PlanApplyOperation.create(plan);
        applyTags(plan, operation, ignored -> { });
        Path manifest = new ImmichTagManifestWriter().writeCsv(
                session.tagPlan(config.keeperTag(), config.unusedTag(), config.finalNotFoundTag()),
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
        AppLog.info("immich.plan_apply_started", Map.of("planId", AppLog.shortId(plan.id()),
                "operationId", AppLog.shortId(operation.id()), "steps", operation.steps().size()));
        checkpoint.accept(operation);
        for (PlanApplyOperation.Step step : operation.steps()) {
            if (step.state() == PlanApplyOperation.StepState.COMPLETE) {
                continue;
            }
            operation.start(step);
            AppLog.info("immich.plan_step_started", stepFields(plan, operation, step));
            checkpoint.accept(operation);
            try {
                int affected = execute(step, acknowledgedAssets -> {
                    operation.progress(step, acknowledgedAssets);
                    checkpoint.accept(operation);
                });
                operation.complete(step, affected);
                Map<String, Object> fields = stepFields(plan, operation, step);
                fields.put("affectedAssets", affected);
                AppLog.info("immich.plan_step_completed", fields);
                checkpoint.accept(operation);
            } catch (IOException | InterruptedException | RuntimeException ex) {
                operation.fail(step, ex);
                AppLog.error("immich.plan_step_failed", stepFields(plan, operation, step), ex);
                checkpoint.accept(operation);
                throw ex;
            }
        }
        return result(plan, operation, plan.manifest());
    }

    private int execute(PlanApplyOperation.Step step, IntConsumer checkpoint) throws IOException, InterruptedException {
        if (step.assetIds().isEmpty()
                && step.mutation() != PlanApplyOperation.Mutation.CLEAR
                && step.mutation() != PlanApplyOperation.Mutation.RECONCILE) {
            return 0;
        }
        ImmichApi client = ImmutableTagPlan.RAW.equals(step.account()) ? rawClient : finalClient;
        String side = ImmutableTagPlan.RAW.equals(step.account()) ? "RAW-side" : "Final-side";
        int affected;
        try {
            affected = switch (step.resource()) {
                case TAG -> mutateTagStep(client, step, checkpoint);
                case ALBUM -> mutateAlbumStep(client, step, checkpoint);
            };
        } catch (IOException ex) {
            throw mutationFailure(side, step.resource(), step.tag(), ex);
        }
        if (step.mutation() == PlanApplyOperation.Mutation.CLEAR) {
            return affected;
        }
        if (affected != step.assetIds().size()) {
            throw new IOException("Immich " + side + " " + step.mutation().name().toLowerCase()
                    + " operation for " + step.resource().name().toLowerCase() + " '" + step.tag() + "' completed " + affected + " of "
                    + step.assetIds().size() + " requested assets.");
        }
        return affected;
    }

    private int mutateTagStep(ImmichApi client, PlanApplyOperation.Step step, IntConsumer checkpoint)
            throws IOException, InterruptedException {
        ImmichTag tag;
        if (step.mutation() == PlanApplyOperation.Mutation.ADD) {
            tag = client.ensureTag(step.tag());
        } else {
            tag = existingTag(client, step.tag());
            if (tag == null) {
                // The obsolete state is already absent, so reconciliation is complete.
                return step.assetIds().size();
            }
        }
        return mutateTagsInBatches(client, tag.id(), step.assetIds(), step.mutation(), step.affectedAssets(), checkpoint);
    }

    private int mutateAlbumStep(ImmichApi client, PlanApplyOperation.Step step, IntConsumer checkpoint)
            throws IOException, InterruptedException {
        if (step.mutation() == PlanApplyOperation.Mutation.CLEAR) {
            // Legacy apply operations used to clear Albums before adding the
            // desired assets back. New operations reconcile in one diffed step;
            // treating old clear steps as complete avoids unnecessary churn.
            return 0;
        }
        ImmichAlbum album;
        if (step.mutation() == PlanApplyOperation.Mutation.ADD) {
            album = client.ensureAlbum(step.tag());
            return reconcileAlbumMembers(client, album.id(), step.assetIds(), false, checkpoint);
        } else if (step.mutation() == PlanApplyOperation.Mutation.RECONCILE) {
            album = step.assetIds().isEmpty() ? existingAlbum(client, step.tag()) : client.ensureAlbum(step.tag());
            if (album == null) {
                return 0;
            }
            return reconcileAlbumMembers(client, album.id(), step.assetIds(), true, checkpoint);
        } else {
            album = existingAlbum(client, step.tag());
            if (album == null) {
                // Albums are additive; a missing old Album needs no removal.
                return step.assetIds().size();
            }
            return mutateAlbumInBatches(client, album.id(), step.assetIds(), step.mutation(), step.affectedAssets(), checkpoint);
        }
    }

    private int reconcileAlbumMembers(
            ImmichApi client,
            String albumId,
            List<String> desiredAssetIds,
            boolean removeStaleMembers,
            IntConsumer checkpoint
    ) throws IOException, InterruptedException {
        List<String> desired = new ArrayList<>(new LinkedHashSet<>(desiredAssetIds));
        validateAcknowledgedCount(0, desired.size());
        Set<String> desiredSet = new LinkedHashSet<>(desired);
        Set<String> currentSet = new LinkedHashSet<>(client.albumAssetIds(albumId));

        List<String> stale = new ArrayList<>();
        if (removeStaleMembers) {
            for (String current : currentSet) {
                if (!desiredSet.contains(current)) {
                    stale.add(current);
                }
            }
        }

        List<String> missing = new ArrayList<>();
        for (String assetId : desired) {
            if (!currentSet.contains(assetId)) {
                missing.add(assetId);
            }
        }

        if (!stale.isEmpty()) {
            mutateAlbumInBatches(client, albumId, stale, PlanApplyOperation.Mutation.REMOVE, 0, ignored -> { });
        }
        if (!missing.isEmpty()) {
            mutateAlbumInBatches(client, albumId, missing, PlanApplyOperation.Mutation.ADD, 0, ignored -> { });
        }
        AppLog.info("immich.album_reconciled", Map.of("desiredAssets", desired.size(),
                "alreadyMembers", desired.size() - missing.size(), "addedAssets", missing.size(), "removedAssets", stale.size()));
        checkpoint.accept(desired.size());
        return desired.size();
    }

    private ImmichTag existingTag(ImmichApi client, String name) throws IOException, InterruptedException {
        for (ImmichTag tag : client.tags()) {
            if (tag.name().equalsIgnoreCase(name) || tag.value().equalsIgnoreCase(name)) {
                return tag;
            }
        }
        return null;
    }

    private ImmichAlbum existingAlbum(ImmichApi client, String name) throws IOException, InterruptedException {
        for (ImmichAlbum album : client.albums()) {
            if (album.name().equalsIgnoreCase(name)) {
                return album;
            }
        }
        return null;
    }

    private int mutateTagsInBatches(
            ImmichApi client,
            String tagId,
            List<String> assetIds,
            PlanApplyOperation.Mutation mutation,
            int alreadyAcknowledged,
            IntConsumer checkpoint
    ) throws IOException, InterruptedException {
        List<String> uniqueIds = new ArrayList<>(new LinkedHashSet<>(assetIds));
        validateAcknowledgedCount(alreadyAcknowledged, uniqueIds.size());
        int tagged = alreadyAcknowledged;
        int batchSize = config.mutationBatchSize();
        int totalBatches = (uniqueIds.size() + batchSize - 1) / batchSize;
        for (int start = alreadyAcknowledged; start < uniqueIds.size(); start += batchSize) {
            int end = Math.min(uniqueIds.size(), start + batchSize);
            List<String> batch = uniqueIds.subList(start, end);
            int batchAffected;
            try {
                batchAffected = mutation == PlanApplyOperation.Mutation.ADD
                        ? client.tagAssets(tagId, batch)
                        : client.untagAssets(tagId, batch);
            } catch (IOException ex) {
                throw batchFailure("tag", start / batchSize + 1, totalBatches, batch.size(), ex);
            }
            requireEntireBatch("tag", start / batchSize + 1, totalBatches, batch.size(), batchAffected);
            tagged += batchAffected;
            AppLog.info("immich.mutation_batch_completed", Map.of("resource", "tag", "mutation", mutation.name(),
                    "batch", start / batchSize + 1, "batchCount", totalBatches, "requested", batch.size(), "acknowledged", tagged));
            checkpoint.accept(tagged);
        }
        return tagged;
    }

    private int mutateAlbumInBatches(
            ImmichApi client,
            String albumId,
            List<String> assetIds,
            PlanApplyOperation.Mutation mutation,
            int alreadyAcknowledged,
            IntConsumer checkpoint
    ) throws IOException, InterruptedException {
        List<String> uniqueIds = new ArrayList<>(new LinkedHashSet<>(assetIds));
        validateAcknowledgedCount(alreadyAcknowledged, uniqueIds.size());
        int affected = alreadyAcknowledged;
        int batchSize = config.mutationBatchSize();
        int totalBatches = (uniqueIds.size() + batchSize - 1) / batchSize;
        for (int start = alreadyAcknowledged; start < uniqueIds.size(); start += batchSize) {
            int end = Math.min(uniqueIds.size(), start + batchSize);
            List<String> batch = uniqueIds.subList(start, end);
            int batchAffected;
            try {
                batchAffected = mutation == PlanApplyOperation.Mutation.ADD
                        ? client.addAssetsToAlbum(albumId, batch)
                        : client.removeAssetsFromAlbum(albumId, batch);
            } catch (IOException ex) {
                throw batchFailure("album", start / batchSize + 1, totalBatches, batch.size(), ex);
            }
            requireEntireBatch("album", start / batchSize + 1, totalBatches, batch.size(), batchAffected);
            affected += batchAffected;
            AppLog.info("immich.mutation_batch_completed", Map.of("resource", "album", "mutation", mutation.name(),
                    "batch", start / batchSize + 1, "batchCount", totalBatches, "requested", batch.size(), "acknowledged", affected));
            checkpoint.accept(affected);
        }
        return affected;
    }

    private void validateAcknowledgedCount(int count, int total) {
        if (count < 0 || count > total) {
            throw new IllegalStateException("Persisted tag-operation progress is invalid. Start a new dry-run plan.");
        }
    }

    private void requireEntireBatch(String resource, int batchNumber, int totalBatches, int requested, int affected)
            throws IOException {
        if (affected != requested) {
            throw new IOException("Immich " + resource + " batch " + batchNumber + " of " + totalBatches
                    + " completed " + affected + " of " + requested + " requested assets.");
        }
    }

    private IOException batchFailure(String resource, int batchNumber, int totalBatches, int assetCount, IOException cause) {
        return new IOException("Immich " + resource + " batch " + batchNumber + " of " + totalBatches
                + " (" + assetCount + " assets) failed: " + cause.getMessage(), cause);
    }

    private IOException mutationFailure(String side, PlanApplyOperation.Resource resource, String name, IOException cause) {
        if (isTimeout(cause)) {
            return new IOException("Immich " + side + " " + resource.name().toLowerCase() + " operation timed out for '"
                    + name + "'. Immich did not respond before the configured request timeout; this is not an HTTP "
                    + "permission denial. Retry to resume from the last acknowledged batch: " + cause.getMessage(), cause);
        }
        return new IOException("Immich " + side + " " + resource.name().toLowerCase() + " operation failed for '" + name
                + "'. Check that the " + sideKeyHint(side)
                + " can manage " + resource.name().toLowerCase() + "s on that side: " + cause.getMessage(), cause);
    }

    private boolean isTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> stepFields(ImmutableTagPlan plan, PlanApplyOperation operation, PlanApplyOperation.Step step) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", AppLog.shortId(plan.id()));
        values.put("operationId", AppLog.shortId(operation.id()));
        values.put("step", step.id());
        values.put("account", step.account().toLowerCase());
        values.put("resource", step.resource().name().toLowerCase());
        values.put("mutation", step.mutation().name().toLowerCase());
        values.put("label", step.tag());
        values.put("assetCount", step.assetIds().size());
        values.put("alreadyAcknowledged", step.affectedAssets());
        return values;
    }

    private ImmichTagApplyResult result(ImmutableTagPlan plan, PlanApplyOperation operation, Path manifest) {
        return new ImmichTagApplyResult(
                itemCount(plan.rawItems(), ImmutableTagPlan.KEEPER),
                itemCount(plan.rawItems(), ImmutableTagPlan.UNUSED),
                itemCount(plan.rawItems(), ImmutableTagPlan.FINAL_NOT_FOUND),
                itemCount(plan.finalItems(), ImmutableTagPlan.RAW_FOUND),
                itemCount(plan.finalItems(), ImmutableTagPlan.NO_RAW),
                itemCount(plan.finalItems(), ImmutableTagPlan.DUPLICATE),
                operation.affectedAssets("add-raw-keeper"),
                operation.affectedAssets("add-raw-unused"),
                operation.affectedAssets("add-raw-final-not-found"),
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
