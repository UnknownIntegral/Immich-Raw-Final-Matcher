package com.photocull.immich;

import java.util.ArrayList;
import java.util.List;

public record ImmichConfig(
        String url,
        String apiKey,
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
        int maxPages
) {
    public static ImmichConfig fromEnvironment() {
        return new ImmichConfig(
                env("IMMICH_URL"),
                env("IMMICH_API_KEY"),
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
                intEnv("PCA_IMMICH_MAX_PAGES", 10000, 1, 1_000_000)
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
        if (isBlank(effectiveRawApiKey())) {
            missing.add("RAW_IMMICH_API_KEY or IMMICH_API_KEY");
        }
        if (isBlank(effectiveFinalApiKey())) {
            missing.add("FINAL_IMMICH_API_KEY or IMMICH_API_KEY");
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
            missing.add("IMMICH_API_KEY, RAW_IMMICH_API_KEY, or FINAL_IMMICH_API_KEY");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Immich API is not configured. Missing: " + String.join(", ", missing));
        }
    }

    public String effectiveRawApiKey() {
        return firstNonBlank(rawApiKey, apiKey);
    }

    public String effectiveFinalApiKey() {
        return firstNonBlank(finalApiKey, apiKey);
    }

    public String userLookupApiKey() {
        return firstNonBlank(apiKey, firstNonBlank(rawApiKey, finalApiKey));
    }

    public boolean sharedApiKeyConfigured() {
        return !isBlank(apiKey);
    }

    public boolean rawApiKeyConfigured() {
        return !isBlank(effectiveRawApiKey());
    }

    public boolean finalApiKeyConfigured() {
        return !isBlank(effectiveFinalApiKey());
    }

    public String rawApiKeySource() {
        return keySource(rawApiKey, "RAW_IMMICH_API_KEY");
    }

    public String finalApiKeySource() {
        return keySource(finalApiKey, "FINAL_IMMICH_API_KEY");
    }

    public String normalizedUrl() {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
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

    private String keySource(String sideKey, String sideName) {
        if (!isBlank(sideKey)) {
            return sideName;
        }
        if (!isBlank(apiKey)) {
            return "IMMICH_API_KEY";
        }
        return "missing";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
