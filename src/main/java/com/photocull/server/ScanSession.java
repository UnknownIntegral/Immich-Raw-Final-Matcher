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
    private final Instant createdAt = Instant.now();
    private final List<PhotoFile> raws;
    private final List<PhotoFile> finals;
    private final List<MatchResult> results;
    private final int threshold;

    public ScanSession(List<PhotoFile> raws, List<PhotoFile> finals, List<MatchResult> results, int threshold) {
        this.raws = List.copyOf(raws);
        this.finals = List.copyOf(finals);
        this.results = new ArrayList<>(results);
        this.threshold = threshold;
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

    public List<MatchResult> results() {
        return List.copyOf(results);
    }

    public int threshold() {
        return threshold;
    }

    public synchronized void updateStatus(int index, MatchStatus status) {
        if (index < 0 || index >= results.size()) {
            throw new IllegalArgumentException("Unknown match index: " + index);
        }
        MatchResult current = results.get(index);
        if (current.rawPathOrNull() == null && status != MatchStatus.REJECTED) {
            throw new IllegalArgumentException("Cannot accept a row with no RAW match.");
        }
        results.set(index, current.withStatus(status));
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
            if (result.status() == MatchStatus.AUTO_ACCEPTED || result.status() == MatchStatus.ACCEPTED) {
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
        Set<Path> duplicateFinalPaths = duplicateFinalPaths();
        Map<Path, MatchResult> resultByFinalPath = new HashMap<>();
        List<FinalTagPlanItem> plan = new ArrayList<>();
        for (MatchResult result : results) {
            resultByFinalPath.put(result.finished().path(), result);
            if (result.raw() != null
                    && (result.status() == MatchStatus.AUTO_ACCEPTED || result.status() == MatchStatus.ACCEPTED)) {
                plan.add(new FinalTagPlanItem(
                        result.finished(),
                        rawFoundTag,
                        "accepted RAW match",
                        result.raw().path(),
                        result.finished().immichAssetId(),
                        result.raw().immichAssetId(),
                        result.score()
                ));
            } else if (result.raw() == null || result.status() == MatchStatus.REJECTED) {
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

    public long reviewCount() {
        return results.stream().filter(result -> result.status() == MatchStatus.NEEDS_REVIEW).count();
    }

    public long rawReviewCount() {
        return results.stream()
                .filter(result -> result.status() == MatchStatus.NEEDS_REVIEW)
                .filter(result -> result.rawPathOrNull() != null)
                .count();
    }

    public long keeperCount() {
        return results.stream().filter(result -> result.raw() != null)
                .filter(result -> result.status() == MatchStatus.AUTO_ACCEPTED || result.status() == MatchStatus.ACCEPTED)
                .map(result -> result.raw().path())
                .distinct()
                .count();
    }

    public long unusedCount() {
        return tagPlan().stream().filter(item -> item.matchedFinalPath() == null).count();
    }

    public long rawFoundCount() {
        return finalTagPlan().stream().filter(item -> item.matchedRawPath() != null && item.tag().equals("RAW Found")).count();
    }

    public long noRawCount() {
        return finalTagPlan().stream().filter(item -> item.tag().equals("No RAW")).count();
    }

    public long duplicateCount() {
        return duplicateFinalPaths().size();
    }

    private Set<Path> duplicateFinalPaths() {
        Map<String, List<PhotoFile>> grouped = new HashMap<>();
        for (PhotoFile finished : finals) {
            String key = duplicateKey(finished);
            if (!key.isBlank()) {
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(finished);
            }
        }

        Set<Path> duplicates = new HashSet<>();
        for (List<PhotoFile> group : grouped.values()) {
            if (group.size() < 2) {
                continue;
            }
            long largestSize = group.stream().mapToLong(PhotoFile::sizeBytes).max().orElse(0);
            for (PhotoFile finished : group) {
                if (finished.sizeBytes() < largestSize) {
                    duplicates.add(finished.path());
                }
            }
        }
        return duplicates;
    }

    private String duplicateKey(PhotoFile file) {
        return file.normalizedStem();
    }
}
