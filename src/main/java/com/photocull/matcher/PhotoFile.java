package com.photocull.matcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record PhotoFile(
        Path path,
        Path root,
        String immichAssetId,
        String immichOwnerId,
        String extension,
        String stem,
        String normalizedStem,
        String trailingNumber,
        long sizeBytes,
        Instant lastModified,
        Instant captureTime,
        boolean captureTimeFromMetadata,
        String make,
        String model
) {
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d{3,})$");

    public static PhotoFile from(Path path, Path root) throws IOException {
        String fileName = path.getFileName().toString();
        String extension = extension(fileName);
        String stem = stem(fileName);
        PhotoMetadata metadata = ExifReader.read(path, extension);
        return new PhotoFile(
                path,
                root,
                null,
                null,
                extension,
                stem,
                normalize(stem),
                trailingNumber(stem).orElse(""),
                Files.size(path),
                Files.getLastModifiedTime(path).toInstant(),
                metadata.captureTime(),
                metadata.captureTimeFromMetadata(),
                metadata.make(),
                metadata.model()
        );
    }

    public static PhotoFile fromImmichAsset(
            String assetId,
            String ownerId,
            String originalFileName,
            String originalPath,
            long sizeBytes,
            Instant fileModifiedAt,
            Instant captureTime,
            String make,
            String model
    ) {
        String fileName = firstNonBlank(originalFileName, fileName(originalPath), assetId);
        String extension = extension(fileName);
        String stem = stem(fileName);
        Path path = originalPath == null || originalPath.isBlank() ? Path.of(fileName) : Path.of(originalPath);
        return new PhotoFile(
                path,
                path.getParent() == null ? Path.of("") : path.getParent(),
                blankToNull(assetId),
                blankToNull(ownerId),
                extension,
                stem,
                normalize(stem),
                trailingNumber(stem).orElse(""),
                sizeBytes,
                fileModifiedAt == null ? Instant.EPOCH : fileModifiedAt,
                captureTime,
                captureTime != null,
                blankIfNull(make),
                blankIfNull(model)
        );
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static Optional<String> trailingNumber(String value) {
        Matcher matcher = TRAILING_NUMBER.matcher(normalize(value));
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private static String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
