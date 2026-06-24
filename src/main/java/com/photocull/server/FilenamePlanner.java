package com.photocull.server;

import com.photocull.matcher.MatchResult;
import com.photocull.matcher.MatchStatus;
import com.photocull.matcher.PhotoFile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Produces deterministic, date-only display names. It never writes files; the
 * output belongs in the immutable plan and manifest so it can be reviewed.
 */
final class FilenamePlanner {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private FilenamePlanner() {
    }

    static Map<String, String> plan(ScanSession session) {
        Map<String, PhotoFile> rawForFinal = acceptedRawByFinal(session.results());
        Map<String, PhotoFile> rawsById = rawsById(session.raws());
        Map<String, String> names = new LinkedHashMap<>();
        Map<LocalDate, Integer> nextFinal = new HashMap<>();

        List<PhotoFile> finals = new ArrayList<>(session.finals());
        finals.sort(Comparator.comparing((PhotoFile file) -> dateFor(rawForFinal.get(file.immichAssetId()), file))
                .thenComparing(PhotoFile::lastModified)
                .thenComparing(FilenamePlanner::requiredAssetId));
        for (PhotoFile finalImage : finals) {
            LocalDate day = dateFor(rawForFinal.get(finalImage.immichAssetId()), finalImage);
            int sequence = nextFinal.merge(day, 1, Integer::sum);
            names.put(requiredAssetId(finalImage), filename(day, sequence, finalImage));
        }

        Map<String, String> finalForRaw = finalByRaw(rawForFinal);
        Map<LocalDate, Set<Integer>> occupiedRawSequences = new HashMap<>();
        for (Map.Entry<String, String> entry : finalForRaw.entrySet()) {
            PhotoFile raw = rawById(rawsById, entry.getKey());
            String matchingFinalName = names.get(entry.getValue());
            names.put(requiredAssetId(raw), matchingFinalName.substring(0, matchingFinalName.lastIndexOf('.') + 1) + extension(raw));
            occupiedRawSequences.computeIfAbsent(dateFor(raw, raw), ignored -> new HashSet<>())
                    .add(sequence(matchingFinalName));
        }

        List<PhotoFile> unmatchedRaws = session.raws().stream()
                .filter(raw -> !finalForRaw.containsKey(requiredAssetId(raw)))
                .sorted(Comparator.comparing((PhotoFile raw) -> dateFor(raw, raw))
                        .thenComparing(PhotoFile::lastModified)
                        .thenComparing(FilenamePlanner::requiredAssetId))
                .toList();
        Map<LocalDate, Integer> nextRawSequence = new HashMap<>();
        for (PhotoFile raw : unmatchedRaws) {
            LocalDate day = dateFor(raw, raw);
            Set<Integer> occupied = occupiedRawSequences.computeIfAbsent(day, ignored -> new HashSet<>());
            int sequence = nextRawSequence.getOrDefault(day, 1);
            while (occupied.contains(sequence)) {
                sequence++;
            }
            occupied.add(sequence);
            nextRawSequence.put(day, sequence + 1);
            names.put(requiredAssetId(raw), filename(day, sequence, raw));
        }
        return Map.copyOf(names);
    }

    private static Map<String, PhotoFile> acceptedRawByFinal(List<MatchResult> results) {
        Map<String, PhotoFile> result = new LinkedHashMap<>();
        for (MatchResult match : results) {
            if (match.raw() != null && accepted(match.status())) {
                result.put(requiredAssetId(match.finished()), match.raw());
            }
        }
        return result;
    }

    private static Map<String, String> finalByRaw(Map<String, PhotoFile> rawForFinal) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, PhotoFile> entry : rawForFinal.entrySet()) {
            String rawId = requiredAssetId(entry.getValue());
            // One RAW can be used by several exports. Its own display name
            // follows the first accepted final; each additional final remains
            // uniquely named and the immutable manifest preserves the link.
            result.putIfAbsent(rawId, entry.getKey());
        }
        return result;
    }

    private static Map<String, PhotoFile> rawsById(List<PhotoFile> raws) {
        Map<String, PhotoFile> byId = new HashMap<>();
        for (PhotoFile raw : raws) {
            byId.put(requiredAssetId(raw), raw);
        }
        return byId;
    }

    private static PhotoFile rawById(Map<String, PhotoFile> rawsById, String id) {
        PhotoFile raw = rawsById.get(id);
        if (raw == null) {
            throw new IllegalStateException("Accepted RAW is missing from the scan session.");
        }
        return raw;
    }

    private static LocalDate dateFor(PhotoFile preferred, PhotoFile fallback) {
        Instant instant = preferred != null && preferred.captureTime() != null
                ? preferred.captureTime()
                : fallback.captureTime() != null ? fallback.captureTime() : fallback.lastModified();
        if (instant == null || Instant.EPOCH.equals(instant)) {
            throw new IllegalStateException("Cannot assign a date-only filename because an asset has no capture or file date.");
        }
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static String filename(LocalDate day, int sequence, PhotoFile file) {
        if (sequence < 1 || sequence > 999_999) {
            throw new IllegalStateException("More than 999999 assets require names on " + DAY.format(day) + ".");
        }
        return DAY.format(day) + "-" + String.format("%06d", sequence) + "." + extension(file);
    }

    private static String extension(PhotoFile file) {
        String extension = file.extension();
        if (extension == null || extension.isBlank()) {
            throw new IllegalStateException("Cannot assign a filename to an asset without an extension: " + requiredAssetId(file));
        }
        return extension;
    }

    private static int sequence(String fileName) {
        int dash = fileName.lastIndexOf('-');
        int dot = fileName.lastIndexOf('.');
        return Integer.parseInt(fileName.substring(dash + 1, dot));
    }

    private static boolean accepted(MatchStatus status) {
        return status == MatchStatus.ACCEPTED || status == MatchStatus.AUTO_ACCEPTED;
    }

    private static String requiredAssetId(PhotoFile file) {
        if (file == null || file.immichAssetId() == null || file.immichAssetId().isBlank()) {
            throw new IllegalStateException("Filename planning requires an Immich asset ID for every asset.");
        }
        return file.immichAssetId();
    }
}
