package io.github.sparkrew.fika.api_finder.model;

/**
 * Statistics about paths from a public method to a third-party method
 */
public record PathStats(
        String publicMethod,
        String thirdPartyMethod,
        int totalPaths,
        int shortestPathLength,
        int longestPathLength
) {
    @Override
    public String toString() {
        return String.format("From %s to %s - Paths: %d, Shortest: %d, Longest: %d",
                publicMethod, thirdPartyMethod, totalPaths, shortestPathLength, longestPathLength);
    }
}

