package com.photocull.immich;

import com.photocull.server.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ImmichClient implements ImmichApi {
    private final ImmichConfig config;
    private final HttpClient http;
    private final String apiKey;

    public ImmichClient(ImmichConfig config) {
        this(config, config.userLookupApiKey());
    }

    public ImmichClient(ImmichConfig config, String apiKey) {
        this.config = config;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public List<ImmichUser> users() throws IOException, InterruptedException {
        Object parsed = Json.parse(send("GET", "/users", null));
        List<ImmichUser> users = new ArrayList<>();
        for (Object item : array(parsed)) {
            users.add(ImmichUser.fromJson(object(item)));
        }
        return users;
    }

    public List<ImmichAsset> imageAssetsForOwner(
            String ownerId,
            Set<String> extensions,
            Consumer<String> progress
    ) throws IOException, InterruptedException {
        List<ImmichAsset> assets = new ArrayList<>();
        for (int page = 1; page <= config.maxPages(); page++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "IMAGE");
            body.put("withExif", true);
            body.put("withDeleted", false);
            body.put("size", config.pageSize());
            body.put("page", page);

            Map<String, Object> response = Json.parseObject(send("POST", "/search/metadata", Json.object(body)));
            List<Object> items = array(object(response.get("assets")).get("items"));
            if (items.isEmpty()) {
                break;
            }

            int addedThisPage = 0;
            for (Object item : items) {
                ImmichAsset asset = ImmichAsset.fromJson(object(item));
                if (ownerId.equals(asset.ownerId())
                        && !asset.trashed()
                        && asset.hasExtensionIn(extensions)) {
                    assets.add(asset);
                    addedThisPage++;
                }
            }
            progress.accept("Read Immich page " + page + " for owner " + ownerId + ": "
                    + addedThisPage + " matching assets, " + assets.size() + " total.");

            Object nextPage = object(response.get("assets")).get("nextPage");
            if (nextPage == null || nextPage.toString().isBlank()) {
                break;
            }
        }
        return assets;
    }

    @Override
    public List<ImmichTag> tags() throws IOException, InterruptedException {
        Object parsed = Json.parse(send("GET", "/tags", null));
        List<ImmichTag> tags = new ArrayList<>();
        for (Object item : array(parsed)) {
            tags.add(ImmichTag.fromJson(object(item)));
        }
        return tags;
    }

    public ImmichTag ensureTag(String name) throws IOException, InterruptedException {
        for (ImmichTag tag : tags()) {
            if (tag.name().equalsIgnoreCase(name) || tag.value().equalsIgnoreCase(name)) {
                return tag;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        try {
            return ImmichTag.fromJson(Json.parseObject(send("POST", "/tags", Json.object(body))));
        } catch (IOException conflictOrFailure) {
            for (ImmichTag tag : tags()) {
                if (tag.name().equalsIgnoreCase(name) || tag.value().equalsIgnoreCase(name)) {
                    return tag;
                }
            }
            throw conflictOrFailure;
        }
    }

    public int tagAssets(String tagId, List<String> assetIds) throws IOException, InterruptedException {
        return updateTagAssets("PUT", tagId, assetIds);
    }

    @Override
    public int untagAssets(String tagId, List<String> assetIds) throws IOException, InterruptedException {
        return updateTagAssets("DELETE", tagId, assetIds);
    }

    @Override
    public List<ImmichAlbum> albums() throws IOException, InterruptedException {
        Object parsed = Json.parse(send("GET", "/albums", null));
        List<ImmichAlbum> albums = new ArrayList<>();
        for (Object item : array(parsed)) {
            albums.add(ImmichAlbum.fromJson(object(item)));
        }
        return albums;
    }

    @Override
    public ImmichAlbum ensureAlbum(String name) throws IOException, InterruptedException {
        for (ImmichAlbum album : albums()) {
            if (album.name().equalsIgnoreCase(name)) {
                return album;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("albumName", name);
        try {
            return ImmichAlbum.fromJson(Json.parseObject(send("POST", "/albums", Json.object(body))));
        } catch (IOException conflictOrFailure) {
            for (ImmichAlbum album : albums()) {
                if (album.name().equalsIgnoreCase(name)) {
                    return album;
                }
            }
            throw conflictOrFailure;
        }
    }

    @Override
    public int addAssetsToAlbum(String albumId, List<String> assetIds) throws IOException, InterruptedException {
        return updateAlbumAssets("PUT", albumId, assetIds);
    }

    @Override
    public int removeAssetsFromAlbum(String albumId, List<String> assetIds) throws IOException, InterruptedException {
        return updateAlbumAssets("DELETE", albumId, assetIds);
    }

    @Override
    public void deleteAlbum(String albumId) throws IOException, InterruptedException {
        send("DELETE", "/albums/" + segment(albumId), null);
    }

    @Override
    public void deleteTag(String tagId) throws IOException, InterruptedException {
        send("DELETE", "/tags/" + segment(tagId), null);
    }

    private int updateTagAssets(String method, String tagId, List<String> assetIds) throws IOException, InterruptedException {
        return updateAssetMembership(method, "/tags/" + segment(tagId) + "/assets", assetIds);
    }

    private int updateAlbumAssets(String method, String albumId, List<String> assetIds) throws IOException, InterruptedException {
        return updateAssetMembership(method, "/albums/" + segment(albumId) + "/assets", assetIds);
    }

    /**
     * Updates a tag or Album membership. Immich returns {@code not_found} for a
     * DELETE when the asset is not currently a member. That is already the
     * desired state for reconciliation, so it must be acknowledged as a no-op.
     */
    private int updateAssetMembership(String method, String path, List<String> assetIds) throws IOException, InterruptedException {
        if (assetIds.isEmpty()) {
            return 0;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", assetIds);
        String response = send(method, path, Json.object(body));
        return countBulkResults(response, assetIds.size(), "DELETE".equals(method));
    }

    private static int countBulkResults(String response, int requestedAssets, boolean missingMembershipIsSuccess) {
        if (response == null || response.isBlank()) {
            return requestedAssets;
        }
        Object parsed = Json.parse(response);
        if (!(parsed instanceof List<?>)) {
            return requestedAssets;
        }
        int affected = 0;
        for (Object item : array(parsed)) {
            Map<String, Object> result = object(item);
            if (result.isEmpty()
                    || Boolean.TRUE.equals(result.get("success"))
                    || (missingMembershipIsSuccess && "not_found".equals(result.get("error")))) {
                affected++;
            }
        }
        return affected;
    }

    @Override
    public byte[] thumbnail(String assetId) throws IOException, InterruptedException {
        return sendBytes("GET", "/assets/" + segment(assetId) + "/thumbnail");
    }

    private String send(String method, String path, String body) throws IOException, InterruptedException {
        requireApiConfigured();
        String target = config.apiUrl() + path;
        URI uri;
        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException ex) {
            throw requestFailure(method, path, target, ex, 1);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("x-api-key", apiKey);

        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        HttpRequest builtRequest = request.build();
        int attempts = retryAttemptsFor(method);
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<String> response = http.send(builtRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                if (isTransientStatus(status) && attempt < attempts) {
                    waitBeforeRetry(attempt);
                    continue;
                }
                throw httpFailure(method, path, target, status, response.body(), attempt);
            } catch (IOException ex) {
                if (ex instanceof HttpResponseFailure) {
                    throw ex;
                }
                lastFailure = ex;
                if (attempt < attempts) {
                    waitBeforeRetry(attempt);
                    continue;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw requestFailure(method, path, target, ex, attempt);
            }
        }
        throw requestFailure(method, path, target, lastFailure, attempts);
    }

    private byte[] sendBytes(String method, String path) throws IOException, InterruptedException {
        requireApiConfigured();
        String target = config.apiUrl() + path;
        URI uri;
        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException ex) {
            throw requestFailure(method, path, target, ex, 1);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .header("Accept", "image/*")
                .header("x-api-key", apiKey)
                .GET()
                .build();
        int attempts = retryAttemptsFor(method);
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                if (isTransientStatus(status) && attempt < attempts) {
                    waitBeforeRetry(attempt);
                    continue;
                }
                throw httpFailure(method, path, target, status, "", attempt);
            } catch (IOException ex) {
                if (ex instanceof HttpResponseFailure) {
                    throw ex;
                }
                lastFailure = ex;
                if (attempt < attempts) {
                    waitBeforeRetry(attempt);
                    continue;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw requestFailure(method, path, target, ex, attempt);
            }
        }
        throw requestFailure(method, path, target, lastFailure, attempts);
    }

    private int retryAttemptsFor(String method) {
        return isIdempotent(method) ? config.requestRetryAttempts() : 1;
    }

    private boolean isIdempotent(String method) {
        return "GET".equals(method) || "PUT".equals(method) || "DELETE".equals(method);
    }

    private boolean isTransientStatus(int status) {
        return status == 429 || status >= 500;
    }

    private void waitBeforeRetry(int completedAttempt) throws InterruptedException {
        long delayMillis = Math.min(10_000L, 2_000L << (completedAttempt - 1));
        Thread.sleep(delayMillis);
    }

    private IOException httpFailure(String method, String path, String target, int status, String body, int attempts) {
        String suffix = attempts > 1 ? " after " + attempts + " attempts" : "";
        return new HttpResponseFailure("Immich API " + method + " " + path + " (" + target + ") failed with HTTP "
                + status + suffix + (body == null || body.isBlank() ? "" : ": " + body));
    }

    private static final class HttpResponseFailure extends IOException {
        private HttpResponseFailure(String message) {
            super(message);
        }
    }

    private IOException requestFailure(String method, String path, String target, Exception ex, int attempts) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = "(no message)";
        }
        String suffix = attempts > 1 ? " after " + attempts + " attempts" : "";
        return new IOException("Immich API " + method + " " + path + " (" + target + ") failed" + suffix
                + " before receiving a response: " + ex.getClass().getName() + ": " + message, ex);
    }

    private void requireApiConfigured() {
        List<String> missing = new ArrayList<>();
        if (config.apiUrl().isBlank()) {
            missing.add("IMMICH_URL");
        }
        if (apiKey.isBlank()) {
            missing.add("Immich API key");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Immich API is not configured. Missing: " + String.join(", ", missing));
        }
    }

    private String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<Object> array(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }
}
