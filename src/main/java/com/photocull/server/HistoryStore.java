package com.photocull.server;

import com.photocull.immich.ImmichTagApplyResult;
import com.photocull.matcher.MatchCandidate;
import com.photocull.matcher.MatchResult;
import com.photocull.matcher.MatchStatus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Append-only, fsynced decision journal. The active scan session is a working
 * copy; this journal preserves the decisions that led to every tag plan.
 */
public final class HistoryStore {
    private static final int MAX_READ_EVENTS = 1_000;
    private final Path eventFile;

    public HistoryStore(Path configDir) {
        eventFile = configDir.resolve("history").resolve("decision-events.jsonl");
    }

    public synchronized void recordScanCompleted(ScanSession session) throws IOException {
        Map<String, Object> scan = event("SCAN_COMPLETED", session);
        scan.put("rawCount", session.raws().size());
        scan.put("finalCount", session.finals().size());
        scan.put("matchCount", session.results().size());
        scan.put("reviewCount", session.reviewCount());
        scan.put("autoAcceptThreshold", session.threshold());
        scan.put("autoRejectThreshold", session.autoRejectThreshold());
        scan.put("detail", "Immich scan completed");

        List<Map<String, Object>> decisions = new ArrayList<>();
        for (MatchResult result : session.results()) {
            if (result.status() == MatchStatus.AUTO_ACCEPTED || result.status() == MatchStatus.AUTO_REJECTED) {
                decisions.add(matchEvent("AUTO_DECISION", session, null, result, "AUTO"));
                if (decisions.size() == 500) {
                    append(decisions);
                    decisions.clear();
                }
            }
        }
        append(decisions);
        append(List.of(scan));
    }

    public synchronized void recordReviewDecision(ScanSession session, MatchResult previous, MatchResult updated) throws IOException {
        append(List.of(matchEvent("REVIEW_DECISION", session, previous, updated, "MANUAL")));
    }

    public synchronized void recordReviewUndo(ScanSession session, MatchResult previous, MatchResult restored) throws IOException {
        append(List.of(matchEvent("REVIEW_UNDONE", session, previous, restored, "MANUAL")));
    }

    public synchronized void recordPlanApproved(ScanSession session, ImmutableTagPlan plan) throws IOException {
        Map<String, Object> values = event("PLAN_APPROVED", session);
        values.put("planId", plan.id());
        values.put("planFingerprint", plan.fingerprint());
        values.put("rawItems", plan.rawItems().size());
        values.put("finalItems", plan.finalItems().size());
        values.put("detail", "Immutable dry-run plan approved");
        append(List.of(values));
    }

    public synchronized void recordApplyResult(
            ScanSession session,
            ImmutableTagPlan plan,
            PlanApplyOperation operation,
            ImmichTagApplyResult result
    ) throws IOException {
        Map<String, Object> values = event("PLAN_APPLIED", session);
        values.put("planId", plan.id());
        values.put("operationId", operation.id());
        values.put("operationState", operation.state().name());
        values.put("rawFoundTagged", result.rawFoundTagged());
        values.put("keeperTagged", result.keeperTagged());
        values.put("detail", "Approved plan applied");
        append(List.of(values));
    }

    public synchronized void recordApplyFailure(
            ScanSession session,
            ImmutableTagPlan plan,
            PlanApplyOperation operation,
            Exception failure
    ) throws IOException {
        Map<String, Object> values = event("PLAN_APPLY_FAILED", session);
        values.put("planId", plan.id());
        values.put("operationId", operation == null ? null : operation.id());
        values.put("operationState", operation == null ? "FAILED" : operation.state().name());
        values.put("detail", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        append(List.of(values));
    }

    public synchronized List<Map<String, Object>> list(int requestedLimit, String assetId) throws IOException {
        if (!Files.exists(eventFile)) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(MAX_READ_EVENTS, requestedLimit));
        List<String> lines = Files.readAllLines(eventFile, StandardCharsets.UTF_8);
        List<Map<String, Object>> events = new ArrayList<>();
        for (int index = lines.size() - 1; index >= 0 && events.size() < limit; index--) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            try {
                Map<String, Object> event = Json.parseObject(line);
                if (assetId == null || assetId.isBlank() || containsAsset(event, assetId)) {
                    events.add(event);
                }
            } catch (Exception ignored) {
                // One damaged historical line must not hide later durable records.
            }
        }
        return List.copyOf(events);
    }

    private Map<String, Object> event(String type, ScanSession session) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("eventId", UUID.randomUUID().toString());
        values.put("occurredAt", Instant.now());
        values.put("eventType", type);
        values.put("sessionCreatedAt", session.createdAt());
        return values;
    }

    private Map<String, Object> matchEvent(
            String type,
            ScanSession session,
            MatchResult previous,
            MatchResult updated,
            String source
    ) {
        Map<String, Object> values = event(type, session);
        values.put("source", source);
        values.put("finalAssetId", updated.finished().immichAssetId());
        values.put("finalPath", updated.finished().path());
        values.put("rawAssetId", updated.raw() == null ? null : updated.raw().immichAssetId());
        values.put("rawPath", updated.raw() == null ? null : updated.raw().path());
        values.put("status", updated.status().name());
        values.put("previousStatus", previous == null ? null : previous.status().name());
        values.put("previousRawAssetId", previous == null || previous.raw() == null ? null : previous.raw().immichAssetId());
        values.put("previousRawPath", previous == null || previous.raw() == null ? null : previous.raw().path());
        values.put("previousScore", previous == null ? null : previous.score());
        values.put("score", updated.score());
        values.put("reason", updated.reason());
        values.put("candidateCount", updated.candidates().size());
        Set<String> assetIds = new LinkedHashSet<>();
        if (updated.finished().immichAssetId() != null) {
            assetIds.add(updated.finished().immichAssetId());
        }
        if (updated.raw() != null && updated.raw().immichAssetId() != null) {
            assetIds.add(updated.raw().immichAssetId());
        }
        for (MatchCandidate candidate : updated.candidates()) {
            if (candidate.raw().immichAssetId() != null) {
                assetIds.add(candidate.raw().immichAssetId());
            }
        }
        values.put("assetIds", List.copyOf(assetIds));
        values.put("detail", detail(updated, previous));
        return values;
    }

    private String detail(MatchResult updated, MatchResult previous) {
        if (updated.status() == MatchStatus.NEEDS_REVIEW && previous != null) {
            return "Review decision undone";
        }
        String action = updated.status() == MatchStatus.ACCEPTED || updated.status() == MatchStatus.AUTO_ACCEPTED
                ? "Accepted RAW match" : "Marked final image as having no RAW match";
        if (previous != null && previous.raw() != null && updated.raw() != null
                && !previous.raw().path().equals(updated.raw().path())) {
            action += " (selected an alternate RAW)";
        }
        return action + ": " + updated.reason();
    }

    private void append(List<Map<String, Object>> events) throws IOException {
        if (events.isEmpty()) {
            return;
        }
        Files.createDirectories(eventFile.getParent());
        StringBuilder lines = new StringBuilder();
        for (Map<String, Object> event : events) {
            lines.append(Json.object(event)).append('\n');
        }
        ByteBuffer data = StandardCharsets.UTF_8.encode(lines.toString());
        try (FileChannel channel = FileChannel.open(eventFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (data.hasRemaining()) {
                channel.write(data);
            }
            channel.force(true);
        }
    }

    private boolean containsAsset(Map<String, Object> event, String assetId) {
        if (assetId.equals(event.get("finalAssetId")) || assetId.equals(event.get("rawAssetId"))) {
            return true;
        }
        Object values = event.get("assetIds");
        return values instanceof List<?> list && list.stream().anyMatch(assetId::equals);
    }
}
