package com.photocull.immich;

import java.nio.file.Path;

public record ImmichTagApplyResult(
        int keeperAssets,
        int unusedAssets,
        int rawFoundAssets,
        int noRawAssets,
        int duplicateAssets,
        int keeperTagged,
        int unusedTagged,
        int rawFoundTagged,
        int noRawTagged,
        int duplicateTagged,
        Path manifest
) {
}
