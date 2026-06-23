package com.photocull.immich;

import java.nio.file.Path;

public record ImmichTagApplyResult(
        int keeperAssets,
        int unusedAssets,
        int finalNotFoundAssets,
        int rawFoundAssets,
        int noRawAssets,
        int duplicateAssets,
        int keeperTagged,
        int unusedTagged,
        int finalNotFoundTagged,
        int rawFoundTagged,
        int noRawTagged,
        int duplicateTagged,
        Path manifest
) {
}
