package com.photocull.immich;

import java.util.ArrayList;
import java.util.List;

public record ImmichConfig(
        String url,
        String apiKey,
        String rawUserId,
        String finalUserId,
        String keeperTag,
        String unusedTag,
        String rawFoundTag,
        String noRawTag,
        int pageSize,
        int maxPages
) {
    public static ImmichConfig fromEnvironment() {
        return new ImmichConfig(
                env("IMMICH_URL"),
                env("IMMICH_API_KEY"),
                env("RAW_USER_ID"),
                env("FINAL_USER_ID"),
                firstNonBlank(env("PCA_KEEPER_TAG"), "Keeper"),
                firstNonBlank(env("PCA_UNUSED_TAG"), "not used"),
                firstNonBlank(env("PCA_RAW_FOUND_TAG"), "RAW Found"),
                firstNonBlank(env("PCA_NO_RAW_TAG"), "No RAW"),
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
        if (isBlank(apiKey)) {
            missing.add("IMMICH_API_KEY");
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
        if (isBlank(apiKey)) {
            missing.add("IMMICH_API_KEY");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Immich API is not configured. Missing: " + String.join(", ", missing));
        }
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
