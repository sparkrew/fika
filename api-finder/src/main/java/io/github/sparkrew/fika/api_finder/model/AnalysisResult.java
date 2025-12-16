package io.github.sparkrew.fika.api_finder.model;

import java.util.List;

/**
 * Main analysis result containing all discovered third-party paths
 */
public record AnalysisResult(List<ThirdPartyPath> thirdPartyPaths) {
}
