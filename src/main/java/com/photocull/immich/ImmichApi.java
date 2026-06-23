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

    /**
     * Returns tags visible to this API key. This is used to make reconciliation
     * non-destructive: a missing obsolete tag is treated as already reconciled
     * instead of creating a tag solely in order to remove it.
     */
    List<ImmichTag> tags() throws IOException, InterruptedException;

    int tagAssets(String tagId, List<String> assetIds) throws IOException, InterruptedException;

    int untagAssets(String tagId, List<String> assetIds) throws IOException, InterruptedException;

    List<ImmichAlbum> albums() throws IOException, InterruptedException;

    ImmichAlbum ensureAlbum(String name) throws IOException, InterruptedException;

    int addAssetsToAlbum(String albumId, List<String> assetIds) throws IOException, InterruptedException;

    int removeAssetsFromAlbum(String albumId, List<String> assetIds) throws IOException, InterruptedException;

    void deleteAlbum(String albumId) throws IOException, InterruptedException;

    void deleteTag(String tagId) throws IOException, InterruptedException;

    byte[] thumbnail(String assetId) throws IOException, InterruptedException;
}
