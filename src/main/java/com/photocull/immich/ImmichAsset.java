package com.photocull.immich;

import com.photocull.matcher.PhotoFile;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

public record ImmichAsset(
        String id,
        String ownerId,
        String originalFileName,
        String originalPath,
        String originalMimeType,
        String type,
        Instant fileCreatedAt,
        Instant fileModifiedAt,
        Instant localDateTime,
        boolean trashed,
        String make,
        String model,
        long sizeBytes
) {
    public static ImmichAsset fromJson(Map<String, Object> values) {
        Map<String, Object> exif = object(values.get("exifInfo"));
        return new ImmichAsset(
                string(values.get("id")),
                string(values.get("ownerId")),
                string(values.get("originalFileName")),
                string(values.get("originalPath")),
                string(values.get("originalMimeType")),
                string(values.get("type")),
                instant(values.get("fileCreatedAt")),
                instant(values.get("fileModifiedAt")),
                instant(values.get("localDateTime")),
                bool(values.get("isTrashed")),
                string(exif.get("make")),
                string(exif.get("model")),
                number(exif.get("fileSizeInByte"))
        );
    }

    public PhotoFile toPhotoFile() {
        return PhotoFile.fromImmichAsset(
                id,
                ownerId,
                originalFileName,
                originalPath,
                sizeBytes,
                fileModifiedAt,
                bestCaptureTime(),
                make,
                model
        );
    }

    public boolean hasExtensionIn(java.util.Set<String> extensions) {
        String name = !isBlank(originalFileName) ? originalFileName : originalPath;
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return extensions.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private Instant bestCaptureTime() {
        if (localDateTime != null) {
            return localDateTime;
        }
        return fileCreatedAt;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0;
    }

    private static Instant instant(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.toString());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
