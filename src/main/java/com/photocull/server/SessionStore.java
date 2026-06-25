package com.photocull.server;

import com.photocull.matcher.MatchResult;
import com.photocull.matcher.MatchScoreDetail;
import com.photocull.matcher.MatchStatus;
import com.photocull.matcher.MatchCandidate;
import com.photocull.matcher.PhotoFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SessionStore {
    private final Path stateFile;

    SessionStore(Path configDir) {
        stateFile = configDir.resolve("scan-session.json");
    }

    synchronized void save(ScanSession session, ScanJob job) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("version", 2);
        state.put("session", session == null ? null : sessionJson(session));
        state.put("job", job == null ? null : job.json());
        Path tempFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        Files.createDirectories(stateFile.getParent());
        Files.writeString(tempFile, Json.object(state), StandardCharsets.UTF_8);
        try {
            Files.move(tempFile, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    synchronized StoredState load(Runnable jobChanged) throws IOException {
        if (!Files.exists(stateFile)) {
            return new StoredState(null, null);
        }
        Map<String, Object> state = Json.parseObject(Files.readString(stateFile, StandardCharsets.UTF_8));
        ScanSession session = object(state.get("session")).isEmpty() ? null : session(object(state.get("session")));
        ScanJob job = object(state.get("job")).isEmpty() ? null : ScanJob.restored(object(state.get("job")), jobChanged);
        return new StoredState(session, job);
    }

    synchronized void clear() throws IOException {
        Files.deleteIfExists(stateFile);
        Files.deleteIfExists(stateFile.resolveSibling(stateFile.getFileName() + ".tmp"));
    }

    private Map<String, Object> sessionJson(ScanSession session) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("createdAt", session.createdAt());
        values.put("autoAcceptThreshold", session.threshold());
        values.put("autoRejectThreshold", session.autoRejectThreshold());
        values.put("lastReviewDecisionIndex", session.lastReviewDecisionIndex());
        values.put("revision", session.revision());
        values.put("raws", session.raws().stream().map(this::photoJson).toList());
        values.put("finals", session.finals().stream().map(this::photoJson).toList());
        Map<Path, Integer> rawIndexes = indexes(session.raws());
        Map<Path, Integer> finalIndexes = indexes(session.finals());
        List<Map<String, Object>> results = new ArrayList<>();
        for (MatchResult result : session.results()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("finalIndex", finalIndexes.get(result.finished().path()));
            row.put("rawIndex", result.raw() == null ? null : rawIndexes.get(result.raw().path()));
            row.put("score", result.score());
            row.put("reason", result.reason());
            row.put("status", result.status().name());
            row.put("scoreDetails", scoreDetailsJson(result.scoreDetails()));
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (MatchCandidate candidate : result.candidates()) {
                Integer rawIndex = rawIndexes.get(candidate.raw().path());
                if (rawIndex == null) {
                    continue;
                }
                Map<String, Object> candidateRow = new LinkedHashMap<>();
                candidateRow.put("rawIndex", rawIndex);
                candidateRow.put("score", candidate.score());
                candidateRow.put("reason", candidate.reason());
                candidateRow.put("scoreDetails", scoreDetailsJson(candidate.scoreDetails()));
                candidates.add(candidateRow);
            }
            row.put("candidates", candidates);
            results.add(row);
        }
        values.put("results", results);
        return values;
    }

    private ScanSession session(Map<String, Object> values) {
        List<PhotoFile> raws = array(values.get("raws")).stream().map(item -> photo(object(item))).toList();
        List<PhotoFile> finals = array(values.get("finals")).stream().map(item -> photo(object(item))).toList();
        List<MatchResult> results = new ArrayList<>();
        for (Object item : array(values.get("results"))) {
            Map<String, Object> row = object(item);
            int finalIndex = number(row.get("finalIndex"), -1);
            int rawIndex = number(row.get("rawIndex"), -1);
            if (finalIndex < 0 || finalIndex >= finals.size()) {
                continue;
            }
            PhotoFile raw = rawIndex >= 0 && rawIndex < raws.size() ? raws.get(rawIndex) : null;
            MatchStatus status;
            try {
                status = MatchStatus.valueOf(string(row.get("status"), MatchStatus.NEEDS_REVIEW.name()));
            } catch (IllegalArgumentException ignored) {
                status = MatchStatus.NEEDS_REVIEW;
            }
            List<MatchCandidate> candidates = new ArrayList<>();
            for (Object candidateItem : array(row.get("candidates"))) {
                Map<String, Object> candidateRow = object(candidateItem);
                int candidateRawIndex = number(candidateRow.get("rawIndex"), -1);
                if (candidateRawIndex < 0 || candidateRawIndex >= raws.size()) {
                    continue;
                }
                candidates.add(new MatchCandidate(raws.get(candidateRawIndex), number(candidateRow.get("score"), 0),
                        string(candidateRow.get("reason"), ""), scoreDetails(candidateRow.get("scoreDetails"))));
            }
            results.add(new MatchResult(finals.get(finalIndex), raw, number(row.get("score"), 0),
                    string(row.get("reason"), ""), status, null, candidates, scoreDetails(row.get("scoreDetails"))));
        }
        return ScanSession.restored(instant(values.get("createdAt")), raws, finals, results,
                number(values.get("autoAcceptThreshold"), 90), number(values.get("autoRejectThreshold"), 50),
                number(values.get("lastReviewDecisionIndex"), -1), numberLong(values.get("revision"), 0));
    }

    private Map<Path, Integer> indexes(List<PhotoFile> photos) {
        Map<Path, Integer> indexes = new HashMap<>();
        for (int i = 0; i < photos.size(); i++) {
            indexes.put(photos.get(i).path(), i);
        }
        return indexes;
    }

    private Map<String, Object> photoJson(PhotoFile photo) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("assetId", photo.immichAssetId());
        values.put("ownerId", photo.immichOwnerId());
        values.put("path", photo.path());
        values.put("sizeBytes", photo.sizeBytes());
        values.put("lastModified", photo.lastModified());
        values.put("captureTime", photo.captureTime());
        values.put("make", photo.make());
        values.put("model", photo.model());
        values.put("lensModel", photo.lensModel());
        values.put("fNumber", photo.fNumber());
        values.put("focalLength", photo.focalLength());
        values.put("iso", photo.iso());
        values.put("exposureTime", photo.exposureTime());
        values.put("contentHash", photo.contentHash());
        return values;
    }

    private List<Map<String, Object>> scoreDetailsJson(List<MatchScoreDetail> details) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MatchScoreDetail detail : details) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", detail.key());
            row.put("label", detail.label());
            row.put("weight", detail.weight());
            row.put("points", detail.points());
            row.put("note", detail.note());
            rows.add(row);
        }
        return rows;
    }

    private List<MatchScoreDetail> scoreDetails(Object value) {
        List<MatchScoreDetail> details = new ArrayList<>();
        for (Object item : array(value)) {
            Map<String, Object> row = object(item);
            details.add(new MatchScoreDetail(
                    string(row.get("key"), ""),
                    string(row.get("label"), ""),
                    number(row.get("weight"), 0),
                    number(row.get("points"), 0),
                    string(row.get("note"), "")
            ));
        }
        return details;
    }

    private PhotoFile photo(Map<String, Object> values) {
        String path = string(values.get("path"), "");
        String fileName = Path.of(path.isBlank() ? "unknown" : path).getFileName().toString();
        return PhotoFile.fromImmichAsset(string(values.get("assetId"), null), string(values.get("ownerId"), null),
                fileName, path, numberLong(values.get("sizeBytes"), 0), instant(values.get("lastModified")),
                instantOrNull(values.get("captureTime")), string(values.get("make"), ""), string(values.get("model"), ""),
                string(values.get("lensModel"), ""), decimal(values.get("fNumber")), decimal(values.get("focalLength")),
                integerOrNull(values.get("iso")), string(values.get("exposureTime"), ""), string(values.get("contentHash"), null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<Object> array(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Double decimal(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null || value.toString().isBlank() ? null : Double.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer integerOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null || value.toString().isBlank() ? null : Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long numberLong(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static String string(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static Instant instant(Object value) {
        Instant parsed = instantOrNull(value);
        return parsed == null ? Instant.now() : parsed;
    }

    private static Instant instantOrNull(Object value) {
        try {
            return value == null || value.toString().isBlank() ? null : Instant.parse(value.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    record StoredState(ScanSession session, ScanJob job) {
    }
}
