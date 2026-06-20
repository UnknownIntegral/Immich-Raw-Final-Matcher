package com.photocull.matcher;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class FileMover {
    private static final DateTimeFormatter MANIFEST_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public List<MatchResult> moveAccepted(
            List<MatchResult> originalResults,
            Path destination,
            Consumer<String> progress
    ) throws IOException {
        Files.createDirectories(destination);

        Map<Path, Path> rawSources = new LinkedHashMap<>();
        for (MatchResult result : originalResults) {
            if (result.isAcceptedForMove()) {
                rawSources.putIfAbsent(result.rawPathOrNull(), null);
            }
        }

        Map<Path, Path> movedTargets = new HashMap<>();
        Map<Path, String> errors = new HashMap<>();
        int moved = 0;
        for (Path source : rawSources.keySet()) {
            try {
                if (source == null || !Files.exists(source)) {
                    errors.put(source, "Source file no longer exists");
                    continue;
                }
                Path target = nextAvailableTarget(destination, source.getFileName().toString());
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                movedTargets.put(source, target);
                moved++;
                progress.accept("Moved " + moved + " of " + rawSources.size() + " RAW files...");
            } catch (IOException atomicFailure) {
                try {
                    Path target = nextAvailableTarget(destination, source.getFileName().toString());
                    Files.move(source, target);
                    movedTargets.put(source, target);
                    moved++;
                    progress.accept("Moved " + moved + " of " + rawSources.size() + " RAW files...");
                } catch (IOException moveFailure) {
                    errors.put(source, moveFailure.getMessage());
                }
            }
        }

        List<MatchResult> updated = new ArrayList<>();
        for (MatchResult result : originalResults) {
            if (!result.isAcceptedForMove()) {
                updated.add(result);
                continue;
            }

            Path target = movedTargets.get(result.rawPathOrNull());
            if (target != null) {
                updated.add(result.withMoveStatus(MatchStatus.MOVED, target));
            } else {
                String error = errors.getOrDefault(result.rawPathOrNull(), "Move failed");
                updated.add(result.withMoveStatus(MatchStatus.MOVE_FAILED, null)
                        .withStatus(MatchStatus.MOVE_FAILED));
                progress.accept(error);
            }
        }

        writeManifest(updated, destination);
        return updated;
    }

    private Path nextAvailableTarget(Path destination, String fileName) throws IOException {
        String stem = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            stem = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }

        Path candidate = destination.resolve(fileName);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = destination.resolve(stem + "_" + suffix + extension);
            suffix++;
        }
        return candidate;
    }

    private void writeManifest(List<MatchResult> results, Path destination) throws IOException {
        String fileName = "photo-culling-manifest-" + LocalDateTime.now().format(MANIFEST_TIMESTAMP) + ".csv";
        Path manifest = destination.resolve(fileName);
        try (BufferedWriter writer = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8)) {
            writer.write("status,score,finished_image,raw_original,moved_to,reason");
            writer.newLine();
            for (MatchResult result : results) {
                writer.write(csv(result.status().label()));
                writer.write(',');
                writer.write(Integer.toString(result.score()));
                writer.write(',');
                writer.write(csv(result.finished().path().toString()));
                writer.write(',');
                writer.write(csv(result.rawPathOrNull() == null ? "" : result.rawPathOrNull().toString()));
                writer.write(',');
                writer.write(csv(result.movedTo() == null ? "" : result.movedTo().toString()));
                writer.write(',');
                writer.write(csv(result.reason()));
                writer.newLine();
            }
        }
    }

    private String csv(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}

