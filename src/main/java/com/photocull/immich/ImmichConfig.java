package com.photocull.immich;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ImmichConfig(
        String url,
        String rawApiKey,
        String finalApiKey,
        String rawUserId,
        String finalUserId,
        String keeperTag,
        String unusedTag,
        String rawFoundTag,
        String noRawTag,
        String duplicateTag,
        int pageSize,
        int maxPages,
        int requestTimeoutSeconds,
        int mutationBatchSize,
        int requestRetryAttempts
) {
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 180;
    private static final int DEFAULT_MUTATION_BATCH_SIZE = 100;
    private static final int DEFAULT_REQUEST_RETRY_ATTEMPTS = 3;

    /**
     * Compatibility constructor for callers that do not need to override the
     * resilient mutation defaults.
     */
    public ImmichConfig(
            String url, String rawApiKey, String finalApiKey, String rawUserId, String finalUserId,
            String keeperTag, String unusedTag, String rawFoundTag, String noRawTag, String duplicateTag,
            int pageSize, int maxPages
    ) {
        this(url, rawApiKey, finalApiKey, rawUserId, finalUserId, keeperTag, unusedTag, rawFoundTag, noRawTag,
                duplicateTag, pageSize, maxPages, DEFAULT_REQUEST_TIMEOUT_SECONDS,
                DEFAULT_MUTATION_BATCH_SIZE, DEFAULT_REQUEST_RETRY_ATTEMPTS);
    }

    public static ImmichConfig fromEnvironment() {
        return new ImmichConfig(
                env("IMMICH_URL"),
                env("RAW_IMMICH_API_KEY"),
                env("FINAL_IMMICH_API_KEY"),
                env("RAW_USER_ID"),
                env("FINAL_USER_ID"),
                firstNonBlank(env("PCA_KEEPER_TAG"), "Keeper"),
                firstNonBlank(env("PCA_UNUSED_TAG"), "not used"),
                firstNonBlank(env("PCA_RAW_FOUND_TAG"), "RAW Found"),
                firstNonBlank(env("PCA_NO_RAW_TAG"), "No RAW"),
                firstNonBlank(env("PCA_DUPLICATE_TAG"), "duplicate"),
                intEnv("PCA_IMMICH_PAGE_SIZE", 1000, 1, 1000),
                intEnv("PCA_IMMICH_MAX_PAGES", 10000, 1, 1_000_000),
                intEnv("PCA_IMMICH_REQUEST_TIMEOUT_SECONDS", DEFAULT_REQUEST_TIMEOUT_SECONDS, 10, 600),
                intEnv("PCA_IMMICH_MUTATION_BATCH_SIZE", DEFAULT_MUTATION_BATCH_SIZE, 1, 500),
                intEnv("PCA_IMMICH_REQUEST_RETRY_ATTEMPTS", DEFAULT_REQUEST_RETRY_ATTEMPTS, 1, 5)
        );
    }

    public boolean isConfigured() {
        return missingFields().isEmpty();
    }

    public List<String> missingFields() {
        List<String> missing = new ArrayList<>();
        if (isBlank(url)) {
            missing.add("IMMICH_URL");
        }
        if (isBlank(rawApiKey)) {
            missing.add("RAW_IMMICH_API_KEY");
        }
        if (isBlank(finalApiKey)) {
            missing.add("FINAL_IMMICH_API_KEY");
        }
        if (isBlank(rawUserId)) {
            missing.add("RAW_USER_ID");
        }
        if (isBlank(finalUserId)) {
            missing.add("FINAL_USER_ID");
        }
        return missing;
    }

    public void requireConfigured() {
        List<String> missing = missingFields();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Immich is not configured. Missing: " + String.join(", ", missing));
        }
    }

    public void requireApiConfigured() {
        List<String> missing = new ArrayList<>();
        if (isBlank(url)) {
            missing.add("IMMICH_URL");
        }
        if (isBlank(userLookupApiKey())) {
            missing.add("RAW_IMMICH_API_KEY or FINAL_IMMICH_API_KEY");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Immich API is not configured. Missing: " + String.join(", ", missing));
        }
    }

    public String effectiveRawApiKey() {
        return rawApiKey == null ? "" : rawApiKey.trim();
    }

    public String effectiveFinalApiKey() {
        return finalApiKey == null ? "" : finalApiKey.trim();
    }

    public String userLookupApiKey() {
        return firstNonBlank(rawApiKey, finalApiKey);
    }

    public boolean rawApiKeyConfigured() {
        return !isBlank(effectiveRawApiKey());
    }

    public boolean finalApiKeyConfigured() {
        return !isBlank(effectiveFinalApiKey());
    }

    public String rawApiKeySource() {
        return isBlank(rawApiKey) ? "missing" : "RAW_IMMICH_API_KEY";
    }

    public String finalApiKeySource() {
        return isBlank(finalApiKey) ? "missing" : "FINAL_IMMICH_API_KEY";
    }

    public String normalizedUrl() {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * Returns the Immich public API base URL. Users normally configure the
     * server root (for example, {@code http://immich-server:2283}); Immich
     * exposes its REST endpoints below {@code /api}. An already suffixed URL
     * is accepted for reverse-proxy configurations.
     */
    public String apiUrl() {
        String base = normalizedUrl();
        if (base.isBlank() || base.endsWith("/api")) {
            return base;
        }
        return base + "/api";
    }

    /**
     * Album membership is intentionally separate from tagging. An empty value
     * disables the corresponding Album action without disabling the tag.
     */
    public Map<String, String> decisionAlbums() {
        Map<String, String> albums = new LinkedHashMap<>();
        albums.put("KEEPER", firstNonBlank(env("PCA_KEEPER_ALBUM"), "PCA - Keeper RAWs"));
        albums.put("UNUSED", firstNonBlank(env("PCA_UNUSED_ALBUM"), "PCA - Unused RAWs"));
        albums.put("RAW_FOUND", firstNonBlank(env("PCA_RAW_FOUND_ALBUM"), "PCA - Finished"));
        albums.put("NO_RAW", firstNonBlank(env("PCA_NO_RAW_ALBUM"), "PCA - No RAW"));
        albums.put("DUPLICATE", firstNonBlank(env("PCA_DUPLICATE_ALBUM"), "PCA - Duplicates"));
        return Map.copyOf(albums);
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static int intEnv(String key, int fallback, int min, int max) {
        String value = env(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String firstNonBlank(String first, String fallback) {
        return isBlank(first) ? fallback : first.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
