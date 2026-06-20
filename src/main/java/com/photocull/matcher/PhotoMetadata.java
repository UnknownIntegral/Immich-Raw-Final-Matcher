package com.photocull.matcher;

import java.time.Instant;

public record PhotoMetadata(
        Instant captureTime,
        boolean captureTimeFromMetadata,
        String make,
        String model
) {
    public static PhotoMetadata empty() {
        return new PhotoMetadata(null, false, "", "");
    }
}

