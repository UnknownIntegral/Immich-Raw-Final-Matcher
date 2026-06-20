package com.photocull.server;

import java.nio.file.Path;

public final class AppPaths {
    private AppPaths() {
    }

    public static Path configDir() {
        String configured = System.getenv("PCA_CONFIG_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home"), ".photo-culling-assistant");
    }
}

