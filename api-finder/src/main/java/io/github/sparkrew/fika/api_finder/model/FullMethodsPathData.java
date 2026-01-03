package io.github.sparkrew.fika.api_finder.model;

import java.util.List;

/**
 * Data structure for paths with full method implementations.
 * Now includes conditionCount for complexity analysis.
 */
public record FullMethodsPathData(
        String entryPoint,
        String thirdPartyMethod,
        List<String> path,
        List<String> methodSources,
        List<String> constructors,
        List<String> setters,
        List<String> imports,
        String testTemplate,
        int conditionCount,
        int callCount
) implements Comparable<FullMethodsPathData> {

    /**
     * Compare paths by condition count first (ascending), then by path length (ascending).
     * This prioritizes simpler paths with fewer conditions.
     * Currently, we have no direct use of this comparison, but it is useful for later analysis.
     */
    @Override
    public int compareTo(FullMethodsPathData other) {
        // Primary sort - condition count (fewer is better)
        int conditionComparison = Integer.compare(this.conditionCount, other.conditionCount);
        if (conditionComparison != 0) {
            return conditionComparison;
        }
        // Secondary sort - path length (shorter is better)
        return Integer.compare(this.path. size(), other.path.size());
    }
}