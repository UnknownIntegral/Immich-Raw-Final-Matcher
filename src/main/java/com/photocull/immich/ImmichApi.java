package com.photocull.immich;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public interface ImmichApi {
    List<ImmichUser> users() throws IOException, InterruptedException;

    List<ImmichAsset> imageAssetsForOwner(
            String ownerId,
            Set<String> extensions,
            Consumer<String> progress
    ) throws IOException, InterruptedException;

    ImmichTag ensureTag(String name) throws IOException, InterruptedException;

    int tagAssets(String tagId, List<String> assetIds) throws IOException, InterruptedException;

    byte[] thumbnail(String assetId) throws IOException, InterruptedException;
}
