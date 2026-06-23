package com.photocull.matcher;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private static final Duration CONFLICTING_CAPTURE_TIME_WINDOW = Duration.ofMinutes(5);
    private static final int CONFLICTING_CAPTURE_TIME_MAX_SCORE = 49;

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
                    .map(match -> new MatchCandidate(match.raw(), match.score(), match.reason()))
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
                            reviewCandidates
                    ));
                } else {
                    MatchStatus status = best.score() >= autoAcceptThreshold
                            ? MatchStatus.AUTO_ACCEPTED
                            : best.score() <= autoRejectThreshold ? MatchStatus.AUTO_REJECTED : MatchStatus.NEEDS_REVIEW;
                    results.add(new MatchResult(finished, best.raw(), best.score(), best.reason(), status, null, reviewCandidates));
                }
            }

            processed++;
            if (processed % 500 == 0) {
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

    private boolean hasConflictingCaptureTimes(PhotoFile finished, PhotoFile raw) {
        return finished.captureTime() != null
                && raw.captureTime() != null
                && Duration.between(finished.captureTime(), raw.captureTime()).abs()
                .compareTo(CONFLICTING_CAPTURE_TIME_WINDOW) > 0;
    }

    private ScoredMatch score(PhotoFile finished, PhotoFile raw) {
        List<String> reasons = new ArrayList<>();
        int nameScore = nameScore(finished, raw, reasons);
        int timeScore = timeScore(finished, raw, reasons);
        int visualScore = visualScore(finished, raw, reasons);
        int metadataScore = metadataScore(finished, raw, reasons);
        int folderDateScore = folderDateScore(finished, raw, reasons);

        int evidenceScore = Math.max(Math.max(nameScore, timeScore), visualScore);
        if (nameScore >= 70 && timeScore >= 58) {
            evidenceScore += 12;
        } else if (nameScore >= 45 && timeScore >= 72) {
            evidenceScore += 10;
        }
        if (visualScore >= 68 && (nameScore >= 45 || timeScore >= 58)) {
            evidenceScore += 8;
        }
        evidenceScore += metadataScore + folderDateScore;
        int score = Math.max(0, Math.min(100, evidenceScore));

        // A filename collision is not enough to overcome contradictory capture metadata.
        // Keep these out of the review band instead of suggesting unrelated photos.
        if (hasConflictingCaptureTimes(finished, raw)) {
            score = Math.min(score, CONFLICTING_CAPTURE_TIME_MAX_SCORE);
            reasons.add("capture timestamps differ by more than 5 minutes");
        }

        if (reasons.isEmpty()) {
            reasons.add("Weak filename similarity");
        }
        return new ScoredMatch(raw, score, evidenceScore, nameScore, String.join("; ", reasons));
    }

    private int nameScore(PhotoFile finished, PhotoFile raw, List<String> reasons) {
        if (finished.normalizedStem().equals(raw.normalizedStem())) {
            reasons.add("same filename stem");
            return 90;
        }
        if (!finished.trailingNumber().isBlank() && finished.trailingNumber().equals(raw.trailingNumber())) {
            reasons.add("same trailing image number " + finished.trailingNumber());
            return 76;
        }
        if (finished.normalizedStem().length() >= 4 && raw.normalizedStem().contains(finished.normalizedStem())) {
            reasons.add("RAW filename contains finished filename");
            return 60;
        }
        if (raw.normalizedStem().length() >= 4 && finished.normalizedStem().contains(raw.normalizedStem())) {
            reasons.add("finished filename contains RAW filename");
            return 60;
        }
        double similarity = diceCoefficient(finished.normalizedStem(), raw.normalizedStem());
        int score = (int) Math.round(similarity * 55.0);
        if (score >= 25) {
            reasons.add("similar filename");
        }
        return score;
    }

    private int timeScore(PhotoFile finished, PhotoFile raw, List<String> reasons) {
        if (finished.captureTime() != null && raw.captureTime() != null) {
            Duration difference = Duration.between(finished.captureTime(), raw.captureTime()).abs();
            if (difference.isZero()) {
                reasons.add("exact capture timestamp");
                return 100;
            }
            long seconds = difference.toSeconds();
            // Near timestamps are common in a burst, so they remain review-level signals.
            if (seconds == 0) {
                reasons.add("capture times are less than 1 second apart");
                return 60;
            }
            if (seconds == 1) {
                reasons.add("capture times are 1 second apart");
                return 52;
            }
            if (seconds <= 2) {
                reasons.add("capture times are 2 seconds apart");
                return 44;
            }
            if (seconds <= 10) {
                reasons.add("capture times match within 10 seconds");
                return 38;
            }
            if (seconds <= 60) {
                reasons.add("capture times match within 1 minute");
                return 30;
            }
            if (seconds <= 300) {
                reasons.add("capture times match within 5 minutes");
                return 22;
            }
            if (seconds <= 3600) {
                reasons.add("capture times match within 1 hour");
                return 12;
            }
            return 0;
        }

        long lastModifiedSeconds = Math.abs(Duration.between(finished.lastModified(), raw.lastModified()).toSeconds());
        if (lastModifiedSeconds <= 60) {
            reasons.add("file modified times are close");
            return 14;
        }
        if (lastModifiedSeconds <= 3600) {
            reasons.add("file modified times are within 1 hour");
            return 8;
        }
        return 0;
    }

    private int metadataScore(PhotoFile finished, PhotoFile raw, List<String> reasons) {
        int score = 0;
        if (!finished.make().isBlank() && !raw.make().isBlank() && same(finished.make(), raw.make())) {
            reasons.add("same camera make");
            score += 2;
        }
        if (!finished.model().isBlank() && !raw.model().isBlank() && same(finished.model(), raw.model())) {
            reasons.add("same camera model");
            score += 5;
        }
        return score;
    }

    private int visualScore(PhotoFile finished, PhotoFile raw, List<String> reasons) {
        return 0;
    }

    private int folderDateScore(PhotoFile finished, PhotoFile raw, List<String> reasons) {
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
            return 4;
        }
        return 0;
    }

    private boolean same(String first, String second) {
        return first.trim().equalsIgnoreCase(second.trim());
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

    private record ScoredMatch(PhotoFile raw, int score, int evidenceScore, int nameScore, String reason) {
    }
}
