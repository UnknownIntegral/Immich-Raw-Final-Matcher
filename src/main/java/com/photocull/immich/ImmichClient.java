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

    private int updateTagAssets(String method, String tagId, List<String> assetIds) throws IOException, InterruptedException {
        if (assetIds.isEmpty()) {
            return 0;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", assetIds);
        String response = send(method, "/tags/" + segment(tagId) + "/assets", Json.object(body));
        if (response == null || response.isBlank()) {
            return assetIds.size();
        }
        Object parsed = Json.parse(response);
        if (!(parsed instanceof List<?>)) {
            return assetIds.size();
        }
        int tagged = 0;
        for (Object item : array(parsed)) {
            Map<String, Object> result = object(item);
            if (result.isEmpty() || Boolean.TRUE.equals(result.get("success"))) {
                tagged++;
            }
        }
        return tagged;
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
            throw requestFailure(method, path, target, ex);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("x-api-key", apiKey);

        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw requestFailure(method, path, target, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw requestFailure(method, path, target, ex);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Immich API " + method + " " + path + " (" + target + ") failed with HTTP "
                    + status + ": " + response.body());
        }
        return response.body();
    }

    private byte[] sendBytes(String method, String path) throws IOException, InterruptedException {
        requireApiConfigured();
        String target = config.apiUrl() + path;
        URI uri;
        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException ex) {
            throw requestFailure(method, path, target, ex);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "image/*")
                .header("x-api-key", apiKey)
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw requestFailure(method, path, target, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw requestFailure(method, path, target, ex);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Immich API " + method + " " + path + " (" + target + ") failed with HTTP "
                    + response.statusCode());
        }
        return response.body();
    }

    private IOException requestFailure(String method, String path, String target, Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = "(no message)";
        }
        return new IOException("Immich API " + method + " " + path + " (" + target
                + ") failed before receiving a response: " + ex.getClass().getName() + ": " + message, ex);
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
    private Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<Object> array(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }
}
