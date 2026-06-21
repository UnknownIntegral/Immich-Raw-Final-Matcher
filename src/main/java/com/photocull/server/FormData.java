package com.photocull.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class FormData {
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private FormData() {
    }

    public static Map<String, String> parse(String body) {
        Map<String, String> values = new HashMap<>();
        if (body == null || body.isBlank()) {
            return values;
        }
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator >= 0 ? pair.substring(0, separator) : pair;
            String value = separator >= 0 ? pair.substring(separator + 1) : "";
            values.put(decode(key), decode(value));
        }
        return values;
    }

    public static String body(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        long declaredLength = exchange.getRequestHeaders().getFirst("Content-Length") == null
                ? -1
                : parseLength(exchange.getRequestHeaders().getFirst("Content-Length"));
        if (declaredLength > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        try (InputStream body = exchange.getRequestBody()) {
            byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("Request body is too large.");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static long parseLength(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
