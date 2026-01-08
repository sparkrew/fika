package io.github.sparkrew.fika.api_finder.model;

import java.util.List;

/**
 * Data structure for paths with full method implementations.
 */
public record FullMethodsPathData(
        String entryPoint,
        String thirdPartyMethod,
        String directCaller,
        List<String> path,
        List<String> methodSources,
        List<String> constructors,
        List<String> fieldDeclarations,
        List<String> setters,
        List<String> imports,
        String testTemplate,
        int conditionCount,
        int callCount,
        boolean covered
) implements Comparable<FullMethodsPathData> {

    /**
     * Compare paths by path length first (ascending), then by condition count (ascending).
     * This prioritizes shorter paths first.
     * Currently, we have no direct use of this comparison, but it is useful for later analysis.
     */
    @Override
    public int compareTo(FullMethodsPathData other) {
        // Primary sort - path length (shorter is better)
        int pathLengthComparison = Integer.compare(this.path.size(), other.path.size());
        if (pathLengthComparison != 0) {
            return pathLengthComparison;
        }
        // Secondary sort - condition count (fewer is better)
        return Integer.compare(this.conditionCount, other.conditionCount);
    }
}