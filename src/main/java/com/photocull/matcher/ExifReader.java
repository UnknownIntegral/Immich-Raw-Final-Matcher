package com.photocull.matcher;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ExifReader {
    private static final int MAX_TIFF_BYTES = 1024 * 1024;
    private static final Set<String> TIFF_LIKE_RAW = Set.of(
            "cr2", "arw", "dng", "nef", "nrw", "orf", "rw2", "pef", "srw"
    );
    private static final DateTimeFormatter EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT);

    private ExifReader() {
    }

    public static PhotoMetadata read(Path path, String extension) {
        try {
            String lower = extension.toLowerCase(Locale.ROOT);
            if (lower.equals("jpg") || lower.equals("jpeg")) {
                return readJpeg(path);
            }
            if (TIFF_LIKE_RAW.contains(lower)) {
                byte[] data = readPrefix(path, MAX_TIFF_BYTES);
                return parseTiff(data, 0);
            }
        } catch (Exception ignored) {
            return PhotoMetadata.empty();
        }
        return PhotoMetadata.empty();
    }

    private static PhotoMetadata readJpeg(Path path) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            if (input.read() != 0xFF || input.read() != 0xD8) {
                return PhotoMetadata.empty();
            }

            while (true) {
                int markerPrefix = input.read();
                if (markerPrefix < 0) {
                    return PhotoMetadata.empty();
                }
                if (markerPrefix != 0xFF) {
                    continue;
                }

                int marker;
                do {
                    marker = input.read();
                } while (marker == 0xFF);

                if (marker < 0 || marker == 0xD9 || marker == 0xDA) {
                    return PhotoMetadata.empty();
                }

                int length = (input.read() << 8) | input.read();
                if (length < 2) {
                    return PhotoMetadata.empty();
                }
                int payloadLength = length - 2;
                byte[] payload = input.readNBytes(payloadLength);
                if (payload.length != payloadLength) {
                    return PhotoMetadata.empty();
                }
                if (marker == 0xE1 && startsWithExifHeader(payload)) {
                    return parseTiff(payload, 6);
                }
            }
        }
    }

    private static boolean startsWithExifHeader(byte[] payload) {
        return payload.length >= 10
                && payload[0] == 'E'
                && payload[1] == 'x'
                && payload[2] == 'i'
                && payload[3] == 'f'
                && payload[4] == 0
                && payload[5] == 0;
    }

    private static byte[] readPrefix(Path path, int maxBytes) throws IOException {
        long size = Math.min(Files.size(path), maxBytes);
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes((int) size);
        }
    }

    private static PhotoMetadata parseTiff(byte[] data, int base) {
        if (data.length < base + 8) {
            return PhotoMetadata.empty();
        }

        ByteOrder order;
        if (data[base] == 'I' && data[base + 1] == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (data[base] == 'M' && data[base + 1] == 'M') {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            return PhotoMetadata.empty();
        }

        if (ushort(data, base + 2, order) != 42) {
            return PhotoMetadata.empty();
        }

        long ifdOffset = uint(data, base + 4, order);
        Map<Integer, String> strings = new HashMap<>();
        parseIfd(data, base, safeInt(ifdOffset), order, strings, 0);

        String date = firstNonBlank(strings.get(0x9003), strings.get(0x9004), strings.get(0x0132));
        Instant captureTime = parseExifDate(date);
        return new PhotoMetadata(
                captureTime,
                captureTime != null,
                blankIfNull(strings.get(0x010F)),
                blankIfNull(strings.get(0x0110))
        );
    }

    private static void parseIfd(
            byte[] data,
            int base,
            int relativeOffset,
            ByteOrder order,
            Map<Integer, String> strings,
            int depth
    ) {
        if (depth > 3) {
            return;
        }
        int ifd = base + relativeOffset;
        if (ifd < base || ifd + 2 > data.length) {
            return;
        }

        int count = ushort(data, ifd, order);
        int entriesStart = ifd + 2;
        for (int i = 0; i < count; i++) {
            int entry = entriesStart + (i * 12);
            if (entry + 12 > data.length) {
                return;
            }
            int tag = ushort(data, entry, order);
            int type = ushort(data, entry + 2, order);
            long valueCount = uint(data, entry + 4, order);

            if (tag == 0x8769 || tag == 0x8825) {
                long nestedOffset = valueAsUnsigned(data, entry, type, valueCount, order);
                if (nestedOffset > 0 && nestedOffset <= Integer.MAX_VALUE) {
                    parseIfd(data, base, (int) nestedOffset, order, strings, depth + 1);
                }
                continue;
            }

            if (type == 2) {
                String value = asciiValue(data, base, entry, valueCount, order);
                if (!value.isBlank()) {
                    strings.putIfAbsent(tag, value);
                }
            }
        }
    }

    private static String asciiValue(byte[] data, int base, int entry, long valueCount, ByteOrder order) {
        if (valueCount <= 0 || valueCount > Integer.MAX_VALUE) {
            return "";
        }

        int length = (int) valueCount;
        int valuePosition;
        if (length <= 4) {
            valuePosition = entry + 8;
        } else {
            long offset = uint(data, entry + 8, order);
            if (offset > Integer.MAX_VALUE) {
                return "";
            }
            valuePosition = base + (int) offset;
        }
        if (valuePosition < 0 || valuePosition + length > data.length) {
            return "";
        }

        int end = valuePosition;
        while (end < valuePosition + length && data[end] != 0) {
            end++;
        }
        return new String(data, valuePosition, end - valuePosition, java.nio.charset.StandardCharsets.US_ASCII).trim();
    }

    private static long valueAsUnsigned(byte[] data, int entry, int type, long valueCount, ByteOrder order) {
        if (valueCount != 1) {
            return 0;
        }
        return switch (type) {
            case 3 -> ushort(data, entry + 8, order);
            case 4 -> uint(data, entry + 8, order);
            default -> 0;
        };
    }

    private static int ushort(byte[] data, int offset, ByteOrder order) {
        if (offset < 0 || offset + 2 > data.length) {
            return 0;
        }
        return ByteBuffer.wrap(data, offset, 2).order(order).getShort() & 0xFFFF;
    }

    private static long uint(byte[] data, int offset, ByteOrder order) {
        if (offset < 0 || offset + 4 > data.length) {
            return 0;
        }
        return ByteBuffer.wrap(data, offset, 4).order(order).getInt() & 0xFFFFFFFFL;
    }

    private static int safeInt(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) value;
    }

    private static Instant parseExifDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            LocalDateTime local = LocalDateTime.parse(value.trim(), EXIF_DATE);
            return local.atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
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
}

