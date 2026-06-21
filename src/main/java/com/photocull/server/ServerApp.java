package com.photocull.server;

import com.photocull.immich.ImmichConfig;

import java.util.concurrent.CountDownLatch;

public final class ServerApp {
    private ServerApp() {
    }

    public static void main(String[] args) throws Exception {
        int port = readPort();
        PhotoCullServer server = new PhotoCullServer(port, AppPaths.configDir(), ImmichConfig.fromEnvironment());
        server.start();
        System.out.println("Photo Culling Assistant web UI running at http://localhost:" + port);
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
