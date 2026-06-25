package com.photocull.matcher;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class MatchEngine {
    private static final int AMBIGUOUS_RAW_MATCH_MARGIN = 8;
    private static final int AMBIGUOUS_RAW_MATCH_MIN_SCORE = 70;
    private static final int SAME_DAY_TIMESTAMP_CONFLICT_CAP = 89;

    public List<MatchResult> match(
            List<PhotoFile> rawFiles,
            List<PhotoFile> finishedFiles,
            int threshold,
            Consumer<String> progress
    ) {
        return match(rawFiles, finishedFiles, threshold, 0, progress);
    }

    public List<MatchResult> match(
            List<PhotoFile> rawFiles,
            List<PhotoFile> finishedFiles,
            int autoAcceptThreshold,
            int autoRejectThreshold,
            Consumer<String> progress
    ) {
        Map<String, List<PhotoFile>> rawsByStem = groupBy(rawFiles, PhotoFile::normalizedStem);
        Map<String, List<PhotoFile>> rawsByNumber = groupBy(rawFiles, PhotoFile::trailingNumber);
        List<PhotoFile> rawsByTime = rawFiles.stream()
                .filter(raw -> raw.captureTime() != null)
                .sorted(Comparator.comparing(PhotoFile::captureTime))
                .toList();

        List<MatchResult> results = new ArrayList<>();
        int processed = 0;
        for (PhotoFile finished : finishedFiles) {
            Set<PhotoFile> candidates = new HashSet<>();
            addAll(candidates, rawsByStem.get(finished.normalizedStem()));
            if (!finished.trailingNumber().isBlank()) {
                addAll(candidates, rawsByNumber.get(finished.trailingNumber()));
            }
            addTimeCandidates(candidates, rawsByTime, finished.captureTime(), Duration.ofMinutes(5));

            List<ScoredMatch> scoredCandidates = candidates.stream()
                    .map(raw -> score(finished, raw))
                    .filter(match -> match.score() > 0)
                    .sorted(Comparator.comparingInt(ScoredMatch::score).reversed()
                            .thenComparing(Comparator.comparingInt(ScoredMatch::evidenceScore).reversed())
                            .thenComparing(Comparator.comparingInt(ScoredMatch::nameScore).reversed())
                            .thenComparing(match -> match.raw().path().toString()))
                    .toList();
            ScoredMatch best = scoredCandidates.isEmpty() ? null : scoredCandidates.get(0);
            List<MatchCandidate> reviewCandidates = scoredCandidates.stream()
                    .limit(5)
                    .map(match -> new MatchCandidate(match.raw(), match.score(), match.reason(), match.scoreDetails()))
                    .toList();

            if (best == null) {
                results.add(new MatchResult(
                        finished,
                        null,
                        0,
                        "No RAW candidate found from filename or metadata",
                        MatchStatus.AUTO_REJECTED,
                        null,
                        reviewCandidates
                ));
            } else {
                ScoredMatch second = scoredCandidates.size() < 2 ? null : scoredCandidates.get(1);
                boolean exactTimestampMatch = sameCaptureTimestamp(finished, best.raw());
                boolean ambiguousRawMatch = !exactTimestampMatch && best.score() > autoRejectThreshold && second != null && (
                        second.score() >= Math.max(AMBIGUOUS_RAW_MATCH_MIN_SCORE, autoAcceptThreshold - 10)
                                && best.score() - second.score() <= AMBIGUOUS_RAW_MATCH_MARGIN
                );
                if (ambiguousRawMatch) {
                    results.add(new MatchResult(
                            finished,
                            best.raw(),
                            best.score(),
                            best.reason() + "; multiple strong RAW candidates, next best "
                                    + second.raw().path() + " scored " + second.score(),
                            MatchStatus.NEEDS_REVIEW,
                            null,
                            reviewCandidates,
                            best.scoreDetails()
                    ));
                } else {
                    MatchStatus status = best.score() >= autoAcceptThreshold
                            ? MatchStatus.AUTO_ACCEPTED
                            : best.score() <= autoRejectThreshold ? MatchStatus.AUTO_REJECTED : MatchStatus.NEEDS_REVIEW;
                    results.add(new MatchResult(finished, best.raw(), best.score(), best.reason(), status, null, reviewCandidates, best.scoreDetails()));
                }
            }

            processed++;
            if (processed % 500 == 0 || processed == finishedFiles.size()) {
                progress.accept("Matched " + processed + " of " + finishedFiles.size() + " finished images...");
            }
        }

        results.sort(Comparator
                .comparing((MatchResult result) -> result.status() != MatchStatus.NEEDS_REVIEW)
                .thenComparingInt(MatchResult::score)
                .thenComparing(result -> result.finished().path().toString()));
        return results;
    }

    private interface KeyExtractor {
        String key(PhotoFile file);
    }

    private Map<String, List<PhotoFile>> groupBy(List<PhotoFile> files, KeyExtractor extractor) {
        Map<String, List<PhotoFile>> grouped = new HashMap<>();
        for (PhotoFile file : files) {
            String key = extractor.key(file);
            if (!key.isBlank()) {
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(file);
            }
        }
        return grouped;
    }

    private void addAll(Set<PhotoFile> candidates, List<PhotoFile> files) {
        if (files != null) {
            candidates.addAll(files);
        }
    }

    private void addTimeCandidates(Set<PhotoFile> candidates, List<PhotoFile> rawsByTime, Instant time, Duration window) {
        if (time == null || rawsByTime.isEmpty()) {
            return;
        }
        int lower = lowerBound(rawsByTime, time.minus(window));
        for (int i = lower; i < rawsByTime.size(); i++) {
            PhotoFile raw = rawsByTime.get(i);
            if (raw.captureTime().isAfter(time.plus(window))) {
                break;
            }
            candidates.add(raw);
        }
    }

    private int lowerBound(List<PhotoFile> files, Instant time) {
        int low = 0;
        int high = files.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (files.get(mid).captureTime().compareTo(time) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private boolean sameCaptureTimestamp(PhotoFile first, PhotoFile second) {
        return first.captureTime() != null && first.captureTime().equals(second.captureTime());
    }

    private ScoredMatch score(PhotoFile finished, PhotoFile raw) {
        List<String> reasons = new ArrayList<>();
        List<MatchScoreDetail> details = new ArrayList<>();
        MatchScoreDetail incompatibility = exifIncompatibility(finished, raw);
        if (incompatibility != null) {
            reasons.add(incompatibility.note());
            details.add(incompatibility);
            return new ScoredMatch(raw, 0, 0, 0, String.join("; ", reasons), details);
        }

        int nameScore = nameScore(finished, raw, reasons, details);
        int timeScore = timeScore(finished, raw, reasons, details);
        int visualScore = visualScore(finished, raw, reasons, details);
        int metadataScore = metadataScore(finished, raw, reasons, details);
        int folderDateScore = folderDateScore(finished, raw, reasons, details);

        int evidenceScore = Math.max(Math.max(nameScore, timeScore), visualScore);
        if (nameScore >= 70 && timeScore >= 58) {
            evidenceScore += 12;
            details.add(new MatchScoreDetail("combinedEvidence", "Filename + time bonus", 12, 12, "Strong filename evidence and close capture time reinforce each other."));
        } else if (nameScore >= 45 && timeScore >= 72) {
            evidenceScore += 10;
            details.add(new MatchScoreDetail("combinedEvidence", "Filename + time bonus", 10, 10, "Moderate filename evidence and very strong capture time reinforce each other."));
        }
        if (visualScore >= 68 && (nameScore >= 45 || timeScore >= 58)) {
            evidenceScore += 8;
            details.add(new MatchScoreDetail("visualBonus", "Visual + metadata bonus", 8, 8, "Strong visual evidence reinforces filename or time evidence."));
        }
        evidenceScore += metadataScore + folderDateScore;
        int score = Math.max(0, Math.min(100, evidenceScore));
        int cap = timestampConflictCap(finished, raw, reasons);
        if (cap < score) {
            details.add(new MatchScoreDetail("timestampCap", "Timestamp cap", 100, cap - score, "Capture timestamps conflict, so the final score is capped at " + cap + "%."));
        }
        score = Math.min(score, cap);

        if (reasons.isEmpty()) {
            reasons.add("Weak filename similarity");
        }
        return new ScoredMatch(raw, score, score, nameScore, String.join("; ", reasons), details);
    }

    private int timestampConflictCap(PhotoFile finished, PhotoFile raw, List<String> reasons) {
        if (finished.captureTime() == null || raw.captureTime() == null) {
            return 100;
        }
        if (!captureDate(finished).equals(captureDate(raw))) {
            reasons.add("capture dates differ");
            return 0;
        }
        Duration difference = Duration.between(finished.captureTime(), raw.captureTime()).abs();
        long seconds = difference.toSeconds();
        if (seconds <= 3600) {
            return 100;
        }
        reasons.add("capture timestamps differ by more than 1 hour");
        return SAME_DAY_TIMESTAMP_CONFLICT_CAP;
    }

    private LocalDate captureDate(PhotoFile file) {
        return file.captureTime().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private int nameScore(PhotoFile finished, PhotoFile raw, List<String> reasons, List<MatchScoreDetail> details) {
        if (finished.normalizedStem().equals(raw.normalizedStem())) {
            reasons.add("same filename stem");
            details.add(new MatchScoreDetail("filename", "Filename", 90, 90, "Same normalized filename stem."));
            return 90;
        }
        if (!finished.trailingNumber().isBlank() && finished.trailingNumber().equals(raw.trailingNumber())) {
            reasons.add("same trailing image number " + finished.trailingNumber());
            details.add(new MatchScoreDetail("filename", "Filename", 90, 76, "Same trailing image number " + finished.trailingNumber() + "."));
            return 76;
        }
        if (finished.normalizedStem().length() >= 4 && raw.normalizedStem().contains(finished.normalizedStem())) {
            reasons.add("RAW filename contains finished filename");
            details.add(new MatchScoreDetail("filename", "Filename", 90, 60, "RAW filename contains the finished filename."));
            return 60;
        }
        if (raw.normalizedStem().length() >= 4 && finished.normalizedStem().contains(raw.normalizedStem())) {
            reasons.add("finished filename contains RAW filename");
            details.add(new MatchScoreDetail("filename", "Filename", 90, 60, "Finished filename contains the RAW filename."));
            return 60;
        }
        double similarity = diceCoefficient(finished.normalizedStem(), raw.normalizedStem());
        int score = (int) Math.round(similarity * 55.0);
        if (score >= 25) {
            reasons.add("similar filename");
            details.add(new MatchScoreDetail("filename", "Filename", 90, score, "Filename similarity score."));
        }
        return score;
    }

    private int timeScore(PhotoFile finished, PhotoFile raw, List<String> reasons, List<MatchScoreDetail> details) {
        if (finished.captureTime() != null && raw.captureTime() != null) {
            Duration difference = Duration.between(finished.captureTime(), raw.captureTime()).abs();
            if (difference.isZero()) {
                reasons.add("exact capture timestamp");
                details.add(new MatchScoreDetail("captureTimestamp", "Capture timestamp", 100, 100, "Exact capture timestamp."));
                return 100;
            }
            long seconds = difference.toSeconds();
            // Near timestamps are common in a burst, so they remain review-level signals.
            if (seconds == 0) {
                reasons.add("capture times are less than 1 second apart");
                details.add(new MatchScoreDetail("captureTimestamp", "Capture timestamp", 100, 60, "Capture times are less than 1 second apart."));
                return 60;
            }
            if (seconds == 1) {
                reasons.add("capture times are 1 second apart");
                details.add(new MatchScoreDetail("captureTimestamp", "Capture timestamp", 100, 52, "Capture times are 1 second apart."));
                return 52;
            }
            if (seconds <= 2) {
                reasons.add("capture times are 2 seconds apart");
                details.add(new MatchScoreDetail("captureTimestamp", "Capture timestamp", 100, 44, "Capture times are 2 seconds apart."));
                return 44;
            }
            if (seconds <= 10) {
                reasons.add("capture times match within 10 seconds");
                details.add(new MatchScoreDetail("captureTimestamp", "Capture timestamp", 100, 38, "Capture times match within 10 seconds."));
                return 38;
            }
            if (seconds <= 60) {
                reasons.add("capture times match within 1 minute");
                details.add(new MatchScoreDetail("captureTimestamp", "Capture timestamp", 100, 30, "Capture times match within 1 minute."));
                return 30;
            }
            if (seconds <= 300) {
                reasons.add("capture times match within 5 minutes");
                details.add(new MatchScoreDetail("captureTimestamp", "Capture timestamp", 100, 22, "Capture times match within 5 minutes."));
                return 22;
            }
            if (seconds <= 3600) {
                reasons.add("capture times match within 1 hour");
                details.add(new MatchScoreDetail("captureTimestamp", "Capture timestamp", 100, 12, "Capture times match within 1 hour."));
                return 12;
            }
            return 0;
        }

        long lastModifiedSeconds = Math.abs(Duration.between(finished.lastModified(), raw.lastModified()).toSeconds());
        if (lastModifiedSeconds <= 60) {
            reasons.add("file modified times are close");
            details.add(new MatchScoreDetail("modifiedTimestamp", "Modified timestamp", 14, 14, "File modified times are within 1 minute; used only when capture time is missing."));
            return 14;
        }
        if (lastModifiedSeconds <= 3600) {
            reasons.add("file modified times are within 1 hour");
            details.add(new MatchScoreDetail("modifiedTimestamp", "Modified timestamp", 14, 8, "File modified times are within 1 hour; used only when capture time is missing."));
            return 8;
        }
        return 0;
    }

    private int metadataScore(PhotoFile finished, PhotoFile raw, List<String> reasons, List<MatchScoreDetail> details) {
        int score = 0;
        int cameraPoints = 0;
        List<String> cameraNotes = new ArrayList<>();
        if (!finished.make().isBlank() && !raw.make().isBlank() && same(finished.make(), raw.make())) {
            reasons.add("same camera make");
            cameraPoints += 2;
            cameraNotes.add("same make");
        }
        if (!finished.model().isBlank() && !raw.model().isBlank() && same(finished.model(), raw.model())) {
            reasons.add("same camera model");
            cameraPoints += 5;
            cameraNotes.add("same model");
        }
        if (cameraPoints > 0) {
            details.add(new MatchScoreDetail("cameraType", "Camera", 7, cameraPoints, String.join(", ", cameraNotes)));
            score += cameraPoints;
        }
        if (!finished.lensModel().isBlank() && !raw.lensModel().isBlank()
                && same(finished.lensModel(), raw.lensModel())) {
            reasons.add("same lens model");
            details.add(new MatchScoreDetail("lensModel", "Lens", 3, 3, "Same lens model."));
            score += 3;
        }
        if (sameNumber(finished.fNumber(), raw.fNumber(), 0.01)) {
            reasons.add("same aperture");
            details.add(new MatchScoreDetail("fNumber", "Aperture", 2, 2, "Same aperture."));
            score += 2;
        }
        if (sameNumber(finished.focalLength(), raw.focalLength(), 0.1)) {
            reasons.add("same focal length");
            details.add(new MatchScoreDetail("focalLength", "Focal length", 2, 2, "Same focal length."));
            score += 2;
        }
        if (finished.iso() != null && raw.iso() != null && finished.iso().equals(raw.iso())) {
            reasons.add("same ISO");
            details.add(new MatchScoreDetail("iso", "ISO", 2, 2, "Same ISO."));
            score += 2;
        }
        if (sameExposureTime(finished.exposureTime(), raw.exposureTime())) {
            reasons.add("same exposure time");
            details.add(new MatchScoreDetail("exposureTime", "Exposure time", 2, 2, "Same or equivalent exposure time."));
            score += 2;
        }
        return score;
    }

    private int visualScore(PhotoFile finished, PhotoFile raw, List<String> reasons, List<MatchScoreDetail> details) {
        return 0;
    }

    private int folderDateScore(PhotoFile finished, PhotoFile raw, List<String> reasons, List<MatchScoreDetail> details) {
        if (finished.captureTime() == null) {
            return 0;
        }
        LocalDate captureDate = finished.captureTime().atZone(ZoneId.systemDefault()).toLocalDate();
        String path = raw.path().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        String year = String.format("%04d", captureDate.getYear());
        String month = String.format("%02d", captureDate.getMonthValue());
        String day = String.format("%02d", captureDate.getDayOfMonth());
        if (path.contains("/" + year + "/" + month + "/" + day + "/")
                || path.contains("/" + year + "/" + captureDate.getMonthValue() + "/" + captureDate.getDayOfMonth() + "/")) {
            reasons.add("RAW folder date matches capture date");
            details.add(new MatchScoreDetail("folderDate", "RAW folder date", 4, 4, "RAW folder date matches the final capture date."));
            return 4;
        }
        return 0;
    }

    private MatchScoreDetail exifIncompatibility(PhotoFile finished, PhotoFile raw) {
        if (stringsConflict(finished.make(), raw.make())) {
            return new MatchScoreDetail("cameraType", "Camera", 7, 0, "Camera makes differ; EXIF discrepancies cannot match.");
        }
        if (stringsConflict(finished.model(), raw.model())) {
            return new MatchScoreDetail("cameraType", "Camera", 7, 0, "Camera models differ; EXIF discrepancies cannot match.");
        }
        if (stringsConflict(finished.lensModel(), raw.lensModel())) {
            return new MatchScoreDetail("lensModel", "Lens", 3, 0, "Lens models differ; EXIF discrepancies cannot match.");
        }
        if (numbersConflict(finished.fNumber(), raw.fNumber(), 0.01)) {
            return new MatchScoreDetail("fNumber", "Aperture", 2, 0, "Aperture values differ; EXIF discrepancies cannot match.");
        }
        if (numbersConflict(finished.focalLength(), raw.focalLength(), 0.1)) {
            return new MatchScoreDetail("focalLength", "Focal length", 2, 0, "Focal lengths differ; EXIF discrepancies cannot match.");
        }
        if (integersConflict(finished.iso(), raw.iso())) {
            return new MatchScoreDetail("iso", "ISO", 2, 0, "ISO values differ; EXIF discrepancies cannot match.");
        }
        if (exposureTimesConflict(finished.exposureTime(), raw.exposureTime())) {
            return new MatchScoreDetail("exposureTime", "Exposure time", 2, 0, "Exposure times differ; EXIF discrepancies cannot match.");
        }
        return null;
    }

    private boolean stringsConflict(String first, String second) {
        boolean firstBlank = first == null || first.isBlank();
        boolean secondBlank = second == null || second.isBlank();
        if (firstBlank || secondBlank) {
            return firstBlank != secondBlank;
        }
        return !same(first, second);
    }

    private boolean numbersConflict(Double first, Double second, double tolerance) {
        if (first == null || second == null) {
            return first != null || second != null;
        }
        return !sameNumber(first, second, tolerance);
    }

    private boolean integersConflict(Integer first, Integer second) {
        if (first == null || second == null) {
            return first != null || second != null;
        }
        return !first.equals(second);
    }

    private boolean exposureTimesConflict(String first, String second) {
        boolean firstBlank = first == null || first.isBlank();
        boolean secondBlank = second == null || second.isBlank();
        if (firstBlank || secondBlank) {
            return firstBlank != secondBlank;
        }
        return !sameExposureTime(first, second);
    }

    private boolean same(String first, String second) {
        return first.trim().equalsIgnoreCase(second.trim());
    }

    private boolean sameNumber(Double first, Double second, double tolerance) {
        return first != null && second != null && Math.abs(first - second) <= tolerance;
    }

    private boolean sameExposureTime(String first, String second) {
        if (first == null || second == null || first.isBlank() || second.isBlank()) {
            return false;
        }
        Double firstSeconds = exposureSeconds(first);
        Double secondSeconds = exposureSeconds(second);
        if (firstSeconds != null && secondSeconds != null) {
            return Math.abs(firstSeconds - secondSeconds) <= Math.max(0.00001, Math.max(firstSeconds, secondSeconds) * 0.001);
        }
        return same(first, second);
    }

    private Double exposureSeconds(String value) {
        try {
            String trimmed = value.trim();
            int slash = trimmed.indexOf('/');
            if (slash > 0 && slash < trimmed.length() - 1) {
                return Double.parseDouble(trimmed.substring(0, slash).trim())
                        / Double.parseDouble(trimmed.substring(slash + 1).trim());
            }
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private double diceCoefficient(String first, String second) {
        if (first.length() < 2 || second.length() < 2) {
            return first.equals(second) ? 1.0 : 0.0;
        }
        Map<String, Integer> firstBigrams = bigrams(first);
        Map<String, Integer> secondBigrams = bigrams(second);
        int overlap = 0;
        for (Map.Entry<String, Integer> entry : firstBigrams.entrySet()) {
            overlap += Math.min(entry.getValue(), secondBigrams.getOrDefault(entry.getKey(), 0));
        }
        return (2.0 * overlap) / (first.length() + second.length() - 2);
    }

    private Map<String, Integer> bigrams(String value) {
        Map<String, Integer> bigrams = new HashMap<>();
        for (int i = 0; i < value.length() - 1; i++) {
            String bigram = value.substring(i, i + 2);
            bigrams.merge(bigram, 1, Integer::sum);
        }
        return bigrams;
    }

    private record ScoredMatch(PhotoFile raw, int score, int evidenceScore, int nameScore, String reason, List<MatchScoreDetail> scoreDetails) {
    }
}
