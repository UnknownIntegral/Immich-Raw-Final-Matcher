package com.photocull.server;

import com.photocull.matcher.PhotoFile;

import java.nio.file.Path;

public record TagPlanItem(
        PhotoFile raw,
        String tag,
        String basis,
        Path matchedFinalPath,
        String rawAssetId,
        String matchedFinalAssetId,
        int score
) {
}
