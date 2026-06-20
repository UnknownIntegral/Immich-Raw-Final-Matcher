package com.photocull.immich;

import com.photocull.server.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ImmichClient {
    private final ImmichConfig config;
    private final HttpClient http;

    public ImmichClient(ImmichConfig config) {
        this.config = config;
        this.http = HttpClient.newHttpClient();
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
        if (assetIds.isEmpty()) {
            return 0;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", assetIds);
        Object parsed = Json.parse(send("PUT", "/tags/" + segment(tagId) + "/assets", Json.object(body)));
        int tagged = 0;
        for (Object item : array(parsed)) {
            if (Boolean.TRUE.equals(object(item).get("success"))) {
                tagged++;
            }
        }
        return tagged;
    }

    private String send(String method, String path, String body) throws IOException, InterruptedException {
        config.requireApiConfigured();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.normalizedUrl() + path))
                .header("Accept", "application/json")
                .header("x-api-key", config.apiKey());

        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Immich API " + method + " " + path + " failed with HTTP "
                    + status + ": " + response.body());
        }
        return response.body();
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
