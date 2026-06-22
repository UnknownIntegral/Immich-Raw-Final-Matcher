package com.photocull.server;

import com.photocull.matcher.MatchResult;
import com.photocull.matcher.MatchStatus;
import com.photocull.matcher.PhotoFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ScanSession {
    private final Instant createdAt;
    private final List<PhotoFile> raws;
    private final List<PhotoFile> finals;
    private final List<MatchResult> results;
    private final int autoAcceptThreshold;
    private final int autoRejectThreshold;
    private final Set<String> rawAssetIds;
    private final Set<String> finalAssetIds;
    private final Set<Path> duplicateFinalPaths;
    private final Set<Path> possibleDuplicateFinalPaths;
    private final Set<Path> duplicateRawPaths;
    private final Set<Path> possibleDuplicateRawPaths;
    private final Map<Path, Integer> rawRowsByPath;
    private final Map<Path, Integer> acceptedByRaw = new HashMap<>();
    private final Map<Path, Integer> unresolvedByRaw = new HashMap<>();
    private long reviewCount;
    private long rawReviewCount;
    private long keeperCount;
    private long unusedCount;
    private long rawFoundCount;
    private long noRawCount;
    private Integer lastReviewDecisionIndex;

    public ScanSession(List<PhotoFile> raws, List<PhotoFile> finals, List<MatchResult> results, int threshold) {
        this(raws, finals, results, threshold, 0);
    }

    public ScanSession(
            List<PhotoFile> raws,
            List<PhotoFile> finals,
            List<MatchResult> results,
            int autoAcceptThreshold,
            int autoRejectThreshold
    ) {
        this(Instant.now(), raws, finals, results, autoAcceptThreshold, autoRejectThreshold);
    }

    static ScanSession restored(
            Instant createdAt,
            List<PhotoFile> raws,
            List<PhotoFile> finals,
            List<MatchResult> results,
            int autoAcceptThreshold,
            int autoRejectThreshold,
            int lastReviewDecisionIndex
    ) {
        ScanSession session = new ScanSession(createdAt, raws, finals, results, autoAcceptThreshold, autoRejectThreshold);
        if (lastReviewDecisionIndex >= 0 && lastReviewDecisionIndex < session.results.size()) {
            MatchStatus status = session.results.get(lastReviewDecisionIndex).status();
            if (status == MatchStatus.ACCEPTED || status == MatchStatus.REJECTED) {
                session.lastReviewDecisionIndex = lastReviewDecisionIndex;
            }
        }
        return session;
    }

    private ScanSession(
            Instant createdAt,
            List<PhotoFile> raws,
            List<PhotoFile> finals,
            List<MatchResult> results,
            int autoAcceptThreshold,
            int autoRejectThreshold
    ) {
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.raws = List.copyOf(raws);
        this.finals = List.copyOf(finals);
        this.results = new ArrayList<>(results);
        this.autoAcceptThreshold = autoAcceptThreshold;
        this.autoRejectThreshold = autoRejectThreshold;
        this.rawAssetIds = assetIds(this.raws);
        this.finalAssetIds = assetIds(this.finals);
        this.duplicateFinalPaths = Set.copyOf(duplicatePaths(this.finals, true));
        this.possibleDuplicateFinalPaths = Set.copyOf(duplicatePaths(this.finals, false));
        this.duplicateRawPaths = Set.copyOf(duplicatePaths(this.raws, true));
        this.possibleDuplicateRawPaths = Set.copyOf(duplicatePaths(this.raws, false));
        this.rawRowsByPath = pathCounts(this.raws);
        initializeMetrics();
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<PhotoFile> raws() {
        return raws;
    }

    public List<PhotoFile> finals() {
        return finals;
    }

    public synchronized List<MatchResult> results() {
        return List.copyOf(results);
    }

    public int threshold() {
        return autoAcceptThreshold;
    }

    public int autoRejectThreshold() {
        return autoRejectThreshold;
    }

    public boolean isRawAsset(String assetId) {
        return rawAssetIds.contains(assetId);
    }

    public boolean isFinalAsset(String assetId) {
        return finalAssetIds.contains(assetId);
    }

    public synchronized MatchResult updateStatus(int index, MatchStatus status) {
        return updateStatus(index, status, null);
    }

    /**
     * Commits a review decision and, for an acceptance, can switch from the
     * initially suggested RAW to one of the retained scored candidates.
     */
    public synchronized MatchResult updateStatus(int index, MatchStatus status, String selectedRawAssetId) {
        if (index < 0 || index >= results.size()) {
            throw new IllegalArgumentException("Unknown match index: " + index);
        }
        MatchResult current = results.get(index);
        if (current.status() != MatchStatus.NEEDS_REVIEW) {
            throw new IllegalArgumentException("Only a match awaiting review can be accepted or rejected.");
        }
        MatchResult selected = current;
        if (status == MatchStatus.ACCEPTED && selectedRawAssetId != null && !selectedRawAssetId.isBlank()) {
            selected = current.withSelectedRaw(rawCandidate(current, selectedRawAssetId));
        }
        if (selected.rawPathOrNull() == null && status != MatchStatus.REJECTED) {
            throw new IllegalArgumentException("Cannot accept a row with no RAW match.");
        }
        if (current.status() == status) {
            return current;
        }

        Set<Path> affectedRawPaths = new HashSet<>();
        if (current.rawPathOrNull() != null) {
            affectedRawPaths.add(current.rawPathOrNull());
        }
        if (selected.rawPathOrNull() != null) {
            affectedRawPaths.add(selected.rawPathOrNull());
        }
        Map<Path, Boolean> previouslyUnused = new HashMap<>();
        for (Path rawPath : affectedRawPaths) {
            previouslyUnused.put(rawPath, isUnused(rawPath));
        }
        removeMetrics(current);
        MatchResult updated = selected.withStatus(status);
        results.set(index, updated);
        addMetrics(updated);
        for (Path rawPath : affectedRawPaths) {
            updateUnusedCount(rawPath, previouslyUnused.get(rawPath));
        }
        if (status == MatchStatus.ACCEPTED || status == MatchStatus.REJECTED) {
            lastReviewDecisionIndex = index;
        }
        return updated;
    }

    private PhotoFile rawCandidate(MatchResult result, String assetId) {
        return result.candidates().stream()
                .map(candidate -> candidate.raw())
                .filter(raw -> assetId.equals(raw.immichAssetId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The selected RAW is not a candidate for this final image."));
    }

    /** Restores the most recently reviewed match to the queue. */
    public synchronized MatchResult undoLastReviewDecision() {
        if (lastReviewDecisionIndex == null) {
            throw new IllegalStateException("There is no review decision to undo.");
        }
        int index = lastReviewDecisionIndex;
        MatchResult current = results.get(index);
        if (current.status() != MatchStatus.ACCEPTED && current.status() != MatchStatus.REJECTED) {
            lastReviewDecisionIndex = null;
            throw new IllegalStateException("The last review decision can no longer be undone.");
        }

        Path rawPath = current.rawPathOrNull();
        boolean wasUnused = rawPath != null && isUnused(rawPath);
        removeMetrics(current);
        MatchResult restored = current.withStatus(MatchStatus.NEEDS_REVIEW);
        results.set(index, restored);
        addMetrics(restored);
        if (rawPath != null) {
            updateUnusedCount(rawPath, wasUnused);
        }
        lastReviewDecisionIndex = null;
        return restored;
    }

    public synchronized boolean canUndoLastReviewDecision() {
        return lastReviewDecisionIndex != null;
    }

    public synchronized Integer lastReviewDecisionIndex() {
        return lastReviewDecisionIndex;
    }

    public synchronized List<TagPlanItem> tagPlan() {
        return tagPlan("Keeper", "not used");
    }

    public synchronized List<TagPlanItem> tagPlan(String keeperTag, String unusedTag) {
        Map<Path, MatchResult> keeperMatches = new HashMap<>();
        Set<Path> unresolvedRawPaths = new HashSet<>();

        for (MatchResult result : results) {
            Path rawPath = result.rawPathOrNull();
            if (rawPath == null) {
                continue;
            }
            if (isAccepted(result.status())) {
                MatchResult existing = keeperMatches.get(rawPath);
                if (existing == null || result.score() > existing.score()) {
                    keeperMatches.put(rawPath, result);
                }
            } else if (result.status() == MatchStatus.NEEDS_REVIEW) {
                unresolvedRawPaths.add(rawPath);
            }
        }

        List<TagPlanItem> plan = new ArrayList<>();
        for (PhotoFile raw : raws) {
            MatchResult keeper = keeperMatches.get(raw.path());
            if (keeper != null) {
                plan.add(new TagPlanItem(
                        raw,
                        keeperTag,
                        "matched final image",
                        keeper.finished().path(),
                        raw.immichAssetId(),
                        keeper.finished().immichAssetId(),
                        keeper.score()
                ));
            } else if (!unresolvedRawPaths.contains(raw.path())) {
                plan.add(new TagPlanItem(raw, unusedTag, "no accepted final image match", null, raw.immichAssetId(), null, 0));
            }
        }

        plan.sort(Comparator
                .comparing(TagPlanItem::tag)
                .thenComparing(item -> item.raw().path().toString()));
        return plan;
    }

    public synchronized List<FinalTagPlanItem> finalTagPlan() {
        return finalTagPlan("RAW Found", "No RAW", "duplicate");
    }

    public synchronized List<FinalTagPlanItem> finalTagPlan(String rawFoundTag, String noRawTag) {
        return finalTagPlan(rawFoundTag, noRawTag, "duplicate");
    }

    public synchronized List<FinalTagPlanItem> finalTagPlan(String rawFoundTag, String noRawTag, String duplicateTag) {
        Map<Path, MatchResult> resultByFinalPath = new HashMap<>();
        List<FinalTagPlanItem> plan = new ArrayList<>();
        for (MatchResult result : results) {
            resultByFinalPath.put(result.finished().path(), result);
            if (result.raw() != null && isAccepted(result.status())) {
                plan.add(new FinalTagPlanItem(
                        result.finished(),
                        rawFoundTag,
                        "accepted RAW match",
                        result.raw().path(),
                        result.finished().immichAssetId(),
                        result.raw().immichAssetId(),
                        result.score()
                ));
            } else if (result.raw() == null || isRejected(result.status())) {
                plan.add(new FinalTagPlanItem(
                        result.finished(),
                        noRawTag,
                        result.raw() == null ? "no RAW candidate found" : "RAW candidate rejected",
                        result.rawPathOrNull(),
                        result.finished().immichAssetId(),
                        result.raw() == null ? null : result.raw().immichAssetId(),
                        result.score()
                ));
            }
        }
        for (PhotoFile duplicate : finals) {
            if (duplicateFinalPaths.contains(duplicate.path())) {
                MatchResult result = resultByFinalPath.get(duplicate.path());
                PhotoFile raw = result == null ? null : result.raw();
                plan.add(new FinalTagPlanItem(
                        duplicate,
                        duplicateTag,
                        "lower file size duplicate with same final-image filename stem",
                        raw == null ? null : raw.path(),
                        duplicate.immichAssetId(),
                        raw == null ? null : raw.immichAssetId(),
                        result == null ? 0 : result.score()
                ));
            }
        }

        plan.sort(Comparator
                .comparing(FinalTagPlanItem::tag)
                .thenComparing(item -> item.finished().path().toString()));
        return plan;
    }

    public synchronized long reviewCount() {
        return reviewCount;
    }

    public synchronized long rawReviewCount() {
        return rawReviewCount;
    }

    public synchronized long keeperCount() {
        return keeperCount;
    }

    public synchronized long unusedCount() {
        return unusedCount;
    }

    public synchronized long rawFoundCount() {
        return rawFoundCount;
    }

    public synchronized long noRawCount() {
        return noRawCount;
    }

    public long duplicateCount() {
        return duplicateFinalPaths.size();
    }

    public long possibleDuplicateFinalCount() {
        return possibleDuplicateFinalPaths.size();
    }

    public long duplicateRawCount() {
        return duplicateRawPaths.size();
    }

    public long possibleDuplicateRawCount() {
        return possibleDuplicateRawPaths.size();
    }

    private void initializeMetrics() {
        for (MatchResult result : results) {
            addMetrics(result);
        }
        for (Path rawPath : rawRowsByPath.keySet()) {
            if (isUnused(rawPath)) {
                unusedCount += rawRowsByPath.get(rawPath);
            }
        }
    }

    private void addMetrics(MatchResult result) {
        Path rawPath = result.rawPathOrNull();
        if (result.status() == MatchStatus.NEEDS_REVIEW) {
            reviewCount++;
            if (rawPath != null) {
                rawReviewCount++;
                increment(unresolvedByRaw, rawPath);
            }
        }
        if (rawPath != null && isAccepted(result.status())) {
            rawFoundCount++;
            int acceptedBefore = acceptedByRaw.getOrDefault(rawPath, 0);
            increment(acceptedByRaw, rawPath);
            if (acceptedBefore == 0) {
                keeperCount++;
            }
        }
        if (result.raw() == null || isRejected(result.status())) {
            noRawCount++;
        }
    }

    private void removeMetrics(MatchResult result) {
        Path rawPath = result.rawPathOrNull();
        if (result.status() == MatchStatus.NEEDS_REVIEW) {
            reviewCount--;
            if (rawPath != null) {
                rawReviewCount--;
                decrement(unresolvedByRaw, rawPath);
            }
        }
        if (rawPath != null && isAccepted(result.status())) {
            rawFoundCount--;
            if (acceptedByRaw.getOrDefault(rawPath, 0) == 1) {
                keeperCount--;
            }
            decrement(acceptedByRaw, rawPath);
        }
        if (result.raw() == null || isRejected(result.status())) {
            noRawCount--;
        }
    }

    private void updateUnusedCount(Path rawPath, boolean wasUnused) {
        boolean isUnused = isUnused(rawPath);
        if (wasUnused == isUnused) {
            return;
        }
        int rawRows = rawRowsByPath.getOrDefault(rawPath, 0);
        unusedCount += isUnused ? rawRows : -rawRows;
    }

    private boolean isUnused(Path rawPath) {
        return acceptedByRaw.getOrDefault(rawPath, 0) == 0 && unresolvedByRaw.getOrDefault(rawPath, 0) == 0;
    }

    private static boolean isAccepted(MatchStatus status) {
        return status == MatchStatus.AUTO_ACCEPTED || status == MatchStatus.ACCEPTED;
    }

    private static boolean isRejected(MatchStatus status) {
        return status == MatchStatus.REJECTED || status == MatchStatus.AUTO_REJECTED;
    }

    private static void increment(Map<Path, Integer> counts, Path path) {
        counts.merge(path, 1, Integer::sum);
    }

    private static void decrement(Map<Path, Integer> counts, Path path) {
        int next = counts.getOrDefault(path, 0) - 1;
        if (next <= 0) {
            counts.remove(path);
        } else {
            counts.put(path, next);
        }
    }

    private static Map<Path, Integer> pathCounts(List<PhotoFile> files) {
        Map<Path, Integer> counts = new HashMap<>();
        for (PhotoFile file : files) {
            counts.merge(file.path(), 1, Integer::sum);
        }
        return counts;
    }

    private static Set<String> assetIds(List<PhotoFile> files) {
        Set<String> assetIds = new HashSet<>();
        for (PhotoFile file : files) {
            if (file.immichAssetId() != null && !file.immichAssetId().isBlank()) {
                assetIds.add(file.immichAssetId());
            }
        }
        return Set.copyOf(assetIds);
    }

    private static Set<Path> duplicatePaths(List<PhotoFile> files, boolean exactOnly) {
        Map<String, List<PhotoFile>> grouped = new HashMap<>();
        for (PhotoFile file : files) {
            String key = duplicateKey(file, exactOnly);
            if (!key.isBlank()) {
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(file);
            }
        }

        Set<Path> duplicates = new HashSet<>();
        for (List<PhotoFile> group : grouped.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator.comparingLong(PhotoFile::sizeBytes).reversed()
                    .thenComparing(PhotoFile::lastModified, Comparator.reverseOrder())
                    .thenComparing(file -> file.immichAssetId() == null ? "" : file.immichAssetId()));
            for (int i = 1; i < group.size(); i++) {
                duplicates.add(group.get(i).path());
            }
        }
        return duplicates;
    }

    private static String duplicateKey(PhotoFile file, boolean exactOnly) {
        if (exactOnly && file.contentHash() != null && !file.contentHash().isBlank()) {
            return "hash:" + file.contentHash();
        }
        return exactOnly || file.contentHash() != null && !file.contentHash().isBlank()
                ? ""
                : "name:" + file.normalizedStem();
    }
}
