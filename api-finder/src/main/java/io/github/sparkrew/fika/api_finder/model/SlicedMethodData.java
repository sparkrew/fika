package io.github.sparkrew.fika.api_finder.model;

import java.util.List;

/**
 * Format 3: Sliced method data
 * This format includes only the relevant code slices from each method
 * that are necessary to reach the third-party method. This uses data-flow
 * and control-flow analysis to extract minimal code.
 */
public record SlicedMethodData(
        String entryPoint,
        String thirdPartyMethod,
        String thirdPartyPackage,
        List<String> path,
        List<String> methodSlices
) {
}
