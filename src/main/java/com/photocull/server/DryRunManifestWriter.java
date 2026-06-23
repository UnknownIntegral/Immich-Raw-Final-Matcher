package com.photocull.server;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class DryRunManifestWriter {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public Path writeCsv(List<TagPlanItem> plan, Path configDir) throws IOException {
        return writeCsv(plan, List.of(), configDir);
    }

    public Path writeCsv(List<TagPlanItem> rawPlan, List<FinalTagPlanItem> finalPlan, Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Path target = configDir.resolve("photo-culling-dry-run-" + LocalDateTime.now().format(TIMESTAMP) + ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write("account,tag,asset_id,path,matched_asset_id,matched_path,score,basis");
            writer.newLine();
            for (TagPlanItem item : rawPlan) {
                writer.write(csv("raw"));
                writer.write(',');
                writer.write(csv(item.tag()));
                writer.write(',');
                writer.write(csv(item.rawAssetId() == null ? "" : item.rawAssetId()));
                writer.write(',');
                writer.write(csv(item.raw().path().toString()));
                writer.write(',');
                writer.write(csv(item.matchedFinalAssetId() == null ? "" : item.matchedFinalAssetId()));
                writer.write(',');
                writer.write(csv(item.matchedFinalPath() == null ? "" : item.matchedFinalPath().toString()));
                writer.write(',');
                writer.write(Integer.toString(item.score()));
                writer.write(',');
                writer.write(csv(item.basis()));
                writer.newLine();
            }
            for (FinalTagPlanItem item : finalPlan) {
                writer.write(csv("final"));
                writer.write(',');
                writer.write(csv(item.tag()));
                writer.write(',');
                writer.write(csv(item.finalAssetId() == null ? "" : item.finalAssetId()));
                writer.write(',');
                writer.write(csv(item.finished().path().toString()));
                writer.write(',');
                writer.write(csv(item.matchedRawAssetId() == null ? "" : item.matchedRawAssetId()));
                writer.write(',');
                writer.write(csv(item.matchedRawPath() == null ? "" : item.matchedRawPath().toString()));
                writer.write(',');
                writer.write(Integer.toString(item.score()));
                writer.write(',');
                writer.write(csv(item.basis()));
                writer.newLine();
            }
        }
        return target;
    }

    /** Writes the immutable plan that will be applied, rather than recalculating a live session. */
    public Path writeCsv(ImmutableTagPlan plan, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            writer.write("plan_id,plan_fingerprint,account,decision,tag,album,asset_id,path,planned_file_name,matched_asset_id,matched_path,score,basis");
            writer.newLine();
            for (ImmutableTagPlan.PlanItem item : plan.items()) {
                writer.write(csv(plan.id()));
                writer.write(',');
                writer.write(csv(plan.fingerprint()));
                writer.write(',');
                writer.write(csv(item.account().toLowerCase()));
                writer.write(',');
                writer.write(csv(item.decision()));
                writer.write(',');
                writer.write(csv(item.tag()));
                writer.write(',');
                writer.write(csv(item.album()));
                writer.write(',');
                writer.write(csv(item.assetId()));
                writer.write(',');
                writer.write(csv(item.path()));
                writer.write(',');
                writer.write(csv(item.plannedFileName()));
                writer.write(',');
                writer.write(csv(item.matchedAssetId() == null ? "" : item.matchedAssetId()));
                writer.write(',');
                writer.write(csv(item.matchedPath() == null ? "" : item.matchedPath()));
                writer.write(',');
                writer.write(Integer.toString(item.score()));
                writer.write(',');
                writer.write(csv(item.basis()));
                writer.newLine();
            }
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        // CSV is commonly opened in spreadsheet software. Neutralise formula-looking user metadata.
        if (!safe.isBlank() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
