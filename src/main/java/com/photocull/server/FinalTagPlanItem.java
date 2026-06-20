package com.photocull.server;

import com.photocull.matcher.PhotoFile;

import java.nio.file.Path;

public record FinalTagPlanItem(
        PhotoFile finished,
        String tag,
        String basis,
        Path matchedRawPath,
        String finalAssetId,
        String matchedRawAssetId,
        int score
) {
}
