package com.photocull.matcher;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class LocalImageMatcher {
    private static final int HASH_SIZE = 16;
    private static final int HASH_PIXELS = HASH_SIZE * HASH_SIZE;
    private static final int MAX_RAW_SCAN_BYTES = 64 * 1024 * 1024;
    private static final int MAX_EMBEDDED_JPEG_BYTES = 32 * 1024 * 1024;

    private final Map<Path, Optional<ImageHash>> cache = new HashMap<>();

    public int visualScore(PhotoFile finished, PhotoFile raw) {
        Optional<ImageHash> finishedHash = hashForFinishedImage(finished.path());
        Optional<ImageHash> rawHash = hashForRawPreview(raw.path());
        if (finishedHash.isEmpty() || rawHash.isEmpty()) {
            return 0;
        }

        int distance = finishedHash.get().distance(rawHash.get());
        double similarity = 1.0 - (distance / (double) HASH_PIXELS);
        if (similarity >= 0.94) {
            return 94;
        }
        if (similarity >= 0.88) {
            return 82;
        }
        if (similarity >= 0.80) {
            return 68;
        }
        if (similarity >= 0.72) {
            return 48;
        }
        return 0;
    }

    private Optional<ImageHash> hashForFinishedImage(Path path) {
        return cache.computeIfAbsent(path, this::readFinishedHash);
    }

    private Optional<ImageHash> hashForRawPreview(Path path) {
        return cache.computeIfAbsent(path, this::readRawHash);
    }

    private Optional<ImageHash> readFinishedHash(Path path) {
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            return image == null ? Optional.empty() : Optional.of(hash(image));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<ImageHash> readRawHash(Path path) {
        try {
            BufferedImage direct = ImageIO.read(path.toFile());
            if (direct != null) {
                return Optional.of(hash(direct));
            }
        } catch (IOException ignored) {
            // Fall through to embedded JPEG extraction.
        }

        try {
            return extractEmbeddedJpeg(path).flatMap(bytes -> {
                try {
                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                    return image == null ? Optional.empty() : Optional.of(hash(image));
                } catch (IOException ignored) {
                    return Optional.empty();
                }
            });
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<byte[]> extractEmbeddedJpeg(Path path) throws IOException {
        long maxBytes = Math.min(Files.size(path), MAX_RAW_SCAN_BYTES);
        try (InputStream input = Files.newInputStream(path)) {
            ByteArrayOutputStream jpeg = null;
            int previous = -1;
            long scanned = 0;
            int current;
            while (scanned < maxBytes && (current = input.read()) >= 0) {
                scanned++;

                if (jpeg == null) {
                    if (previous == 0xFF && current == 0xD8) {
                        jpeg = new ByteArrayOutputStream();
                        jpeg.write(0xFF);
                        jpeg.write(0xD8);
                    }
                } else {
                    jpeg.write(current);
                    if (jpeg.size() > MAX_EMBEDDED_JPEG_BYTES) {
                        jpeg = null;
                    } else if (previous == 0xFF && current == 0xD9) {
                        byte[] candidate = jpeg.toByteArray();
                        if (candidate.length > 4096 && ImageIO.read(new ByteArrayInputStream(candidate)) != null) {
                            return Optional.of(candidate);
                        }
                        jpeg = null;
                    }
                }

                previous = current;
            }
        }
        return Optional.empty();
    }

    private ImageHash hash(BufferedImage source) {
        BufferedImage scaled = new BufferedImage(HASH_SIZE, HASH_SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, HASH_SIZE, HASH_SIZE, null);
        } finally {
            graphics.dispose();
        }

        int[] values = new int[HASH_PIXELS];
        long total = 0;
        int index = 0;
        for (int y = 0; y < HASH_SIZE; y++) {
            for (int x = 0; x < HASH_SIZE; x++) {
                int gray = scaled.getRaster().getSample(x, y, 0);
                values[index++] = gray;
                total += gray;
            }
        }

        double average = total / (double) HASH_PIXELS;
        long[] bits = new long[4];
        for (int i = 0; i < values.length; i++) {
            if (values[i] >= average) {
                bits[i / Long.SIZE] |= 1L << (i % Long.SIZE);
            }
        }
        return new ImageHash(bits);
    }

    private record ImageHash(long[] bits) {
        int distance(ImageHash other) {
            int distance = 0;
            for (int i = 0; i < bits.length; i++) {
                distance += Long.bitCount(bits[i] ^ other.bits[i]);
            }
            return distance;
        }
    }
}

