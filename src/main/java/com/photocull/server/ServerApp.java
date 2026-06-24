package com.photocull.server;

import com.photocull.immich.ImmichConfig;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class ServerApp {
    private ServerApp() {
    }

    public static void main(String[] args) throws Exception {
        int port = readPort();
        var configDir = AppPaths.configDir();
        AppLog.install(configDir);
        ImmichConfig config = ImmichConfig.fromEnvironment();
        Map<String, Object> startup = new LinkedHashMap<>();
        startup.put("port", port);
        startup.put("configDirectory", configDir.toString());
        startup.put("configDirectoryWritable", Files.isWritable(configDir));
        startup.put("immichConfigured", config.isConfigured());
        startup.put("missingConfiguration", config.missingFields());
        startup.put("pageSize", config.pageSize());
        startup.put("maxPages", config.maxPages());
        startup.put("requestTimeoutSeconds", config.requestTimeoutSeconds());
        startup.put("requestRetryAttempts", config.requestRetryAttempts());
        startup.put("mutationBatchSize", config.mutationBatchSize());
        startup.put("accessTokenConfigured", System.getenv("PCA_ACCESS_TOKEN") != null && !System.getenv("PCA_ACCESS_TOKEN").isBlank());
        startup.put("javaVersion", System.getProperty("java.version"));
        AppLog.info("application.starting", startup);
        PhotoCullServer server = new PhotoCullServer(port, configDir, config);
        server.start();
        System.out.println("Photo Culling Assistant web UI running at http://localhost:" + port
                + "; persistent logs are in " + configDir.resolve("logs") + " (seven-day retention).");
        new CountDownLatch(1).await();
    }

    private static int readPort() {
        String value = firstNonBlank(System.getenv("PCA_PORT"), System.getProperty("pca.port"));
        if (value == null) {
            return 8356;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 8356;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }
}
