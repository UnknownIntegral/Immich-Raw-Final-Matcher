package com.photocull.immich;

import com.photocull.server.FinalTagPlanItem;
import com.photocull.server.TagPlanItem;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ImmichTagManifestWriter {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public Path writeCsv(List<TagPlanItem> plan, Path configDir) throws IOException {
        return writeCsv(plan, List.of(), configDir);
    }

    public Path writeCsv(List<TagPlanItem> rawPlan, List<FinalTagPlanItem> finalPlan, Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Path target = configDir.resolve("photo-culling-tag-apply-" + LocalDateTime.now().format(TIMESTAMP) + ".csv");
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

    private String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isBlank() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
