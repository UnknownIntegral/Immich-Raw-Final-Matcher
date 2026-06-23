package com.photocull.immich;

import java.util.Map;

/** Minimal Album representation used for safe lookup and membership updates. */
public record ImmichAlbum(String id, String name) {
    public static ImmichAlbum fromJson(Map<String, Object> values) {
        return new ImmichAlbum(string(values.get("id")), string(values.get("albumName")));
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
