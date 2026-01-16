package io.github.sparkrew.fika.api_finder;

import io.github.sparkrew.fika.api_finder.model.AnalysisResult;
import io.github.sparkrew.fika.api_finder.model.PathNode;
import io.github.sparkrew.fika.api_finder.model.PathStats;
import io.github.sparkrew.fika.api_finder.model.ThirdPartyPath;
import io.github.sparkrew.fika.api_finder.utils.PackageMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.callgraph.CallGraph;
import sootup.callgraph.CallGraphAlgorithm;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static io.github.sparkrew.fika.api_finder.PathWriter.writePathStatsToJson;

public class MethodExtractor {

    static final Logger log = LoggerFactory.getLogger(MethodExtractor.class);
    static Set<String> ignoredPrefixes;

    /**
     * This method processes the JAR file to extract third party API calls and their paths.
     * It initializes the call graph, and finds paths that involve third-party method calls.
     *
     * @param pathToJar      Path to the JAR file to analyze.
     * @param reportPath     Path where the analysis report will be written.
     * @param packageName    The package name of the project under consideration to filter the events.
     * @param packageMapPath Path to the package map file that contains the mapping of package names to Maven coordinates.
     * @param sourceRootPath Path to the project source code root directory (optional, can be null). If provided, actual source code will be extracted instead of Jimple IR.
     * @param jacocoHtmlDirs List of JaCoCo HTML report directories to filter already covered methods (optional, can be empty).
     */
    public static void process(String pathToJar, String reportPath, String packageName, Path packageMapPath,
                               String sourceRootPath, List<File> jacocoHtmlDirs, boolean enableAnalysisLogs) {
        // Start reading the jar with sootup. Here we use all the public methods as the entry points.
        // That means we don't plan to do anything (generate tests etc) for private methods.
        // Don't want any more complications with reflections and all.
        // ToDo: Add the if condition with the enable-logs flag
        // We analyze all third-party method calls in the entire project (including unreachable code)
        // and log them for reference.
        AllMethodCallAnalyzer.analyzeAndLogDetailed(pathToJar, packageName, packageMapPath);
        ignoredPrefixes = PackageMatcher.loadIgnoredPrefixes(packageName);
        JavaView view = createJavaView(pathToJar);
        Set<MethodSignature> entryPoints = detectEntryPoints(view, packageName);
        log.info("Found " + entryPoints.size() + " public methods as entry points.");
        AnalysisResult result = analyzeReachability(view, entryPoints, packageMapPath, jacocoHtmlDirs, sourceRootPath,
                enableAnalysisLogs);
        // Write the main output file.
        PathWriter.writeAllFormats(result, reportPath, sourceRootPath, enableAnalysisLogs);
        log.info("All analysis reports written successfully.");
    }

    private static JavaView createJavaView(String pathToJar) {
        AnalysisInputLocation inputLocation = new JavaClassPathAnalysisInputLocation(pathToJar);
        return new JavaView(inputLocation);
    }

    private static AnalysisResult analyzeReachability(JavaView view, Set<MethodSignature> entryPoints,
                                                      Path packageMapPath, List<File> jacocoHtmlDirs,
                                                      String sourceRootPath, boolean enableAnalysisLogs) {
        List<ThirdPartyPath> thirdPartyPaths = new ArrayList<>();
        List<PathStats> allPathStats = new ArrayList<>();
        try {
            CallGraphAlgorithm cha = new ClassHierarchyAnalysisAlgorithm(view);
            CallGraph cg = cha.initialize(new ArrayList<>(entryPoints));
            // Identify all third-party methods that are actually called in the codebase. We go backwards from
            // third-party methods to public methods to find all paths. This is because we expect this would be more
            // efficient than doing it the other way round, as there are usually much fewer third-party methods than
            // public methods.
            Set<Map.Entry<MethodSignature, MethodSignature>> thirdPartyPairs =
                    findAllThirdPartyMethodPairs(cg, packageMapPath, jacocoHtmlDirs, enableAnalysisLogs);
            log.info("Found {} third-party method call pairs in call graph", thirdPartyPairs.size());
            // Build reverse call graph for efficient backward traversal. Otherwise, it takes painfully long time to
            // run with the forward graph (from public methods to third party methods).
            Map<MethodSignature, Set<MethodSignature>> reverseCallGraph = buildReverseCallGraph(cg);
            // For each third-party call site, find the public method that leads to it
            for (Map.Entry<MethodSignature, MethodSignature> pair : thirdPartyPairs) {
                MethodSignature directCaller = pair.getKey();
                MethodSignature thirdPartyMethod = pair.getValue();
                // Calculate the actual static call count by analyzing source code
                Integer callCount = 1; // Default to 1 if source code is not available
                if (sourceRootPath != null) {
                    try {
                        callCount = SourceCodeExtractor.countMethodInvocations(
                            directCaller, thirdPartyMethod, sourceRootPath);
                    } catch (Exception e) {
                        log.debug("Could not count invocations for path to {}, using default count of 1", 
                                thirdPartyMethod);
                    }
                }
                // If the direct caller is already a public method (entry point),
                // we can directly record this as a path
                if (entryPoints.contains(directCaller)) {
                    List<MethodSignature> path = Arrays.asList(directCaller, thirdPartyMethod);
                    ThirdPartyPath tpPath = new ThirdPartyPath(
                            directCaller,
                            thirdPartyMethod,
                            path,
                            callCount
                    );
                    thirdPartyPaths.add(tpPath);
                } else {
                    // If the direct caller is not public, we need to do BFS to find
                    // the first public methods that can reach this direct caller
                    List<List<MethodSignature>> pathsToPublicMethods = findPathsToFirstPublicCallers(
                            reverseCallGraph,
                            directCaller,
                            entryPoints,
                            packageMapPath
                    );
                    // For each path found from public method to direct caller, append the third party method
                    for (List<MethodSignature> pathToDirectCaller : pathsToPublicMethods) {
                        // The path is in reverse order (from directCaller to publicMethod)
                        // We need to reverse it and append the third party method
                        List<MethodSignature> completePath = new ArrayList<>(pathToDirectCaller);
                        Collections.reverse(completePath);
                        completePath.add(thirdPartyMethod);
                        MethodSignature publicMethod = completePath.get(0);
                        ThirdPartyPath tpPath = new ThirdPartyPath(
                                publicMethod,
                                thirdPartyMethod,
                                completePath,
                                callCount
                        );
                        thirdPartyPaths.add(tpPath);
                    }
                }
            }
            log.info("Collected " + thirdPartyPaths.size() + " third-party paths.");
            if (enableAnalysisLogs) {
                writePathStatsToJson(allPathStats);
            }
        } catch (Exception e) {
            log.error("Failed to initialize call graph.", e);
        }
        return new AnalysisResult(thirdPartyPaths);
    }

    /**
     * Check if a path is direct - meaning only the target method is third-party,
     * all intermediate methods are from the project itself
     */
    private static boolean isDirectPath(List<MethodSignature> path, Path packageMapPath) {
        if (path.size() <= 1) return true;
        // Check all methods except the last one - they should not be third-party
        for (int i = 0; i < path.size() - 1; i++) {
            if (isThirdPartyMethod(path.get(i), packageMapPath)) {
                return false;
            }
        }
        // The last method should be third-party
        return isThirdPartyMethod(path.get(path.size() - 1), packageMapPath);
    }

    /**
     * Find all third-party method call pairs (caller -> third-party method) in the call graph
     */
    private static Set<Map.Entry<MethodSignature, MethodSignature>> findAllThirdPartyMethodPairs(
            CallGraph cg, Path packageMapPath, List<File> jacocoHtmlDirs, boolean enableAnalysisLogs) {
        // We do two iterations because we get coverage check in two steps. First, we register all to check if
        // the same class has multiple calls to the same third party method.
        Set<Map.Entry<MethodSignature, MethodSignature>> allThirdPartyPairs = new HashSet<>();
        for (MethodSignature method : cg.getMethodSignatures()) {
            String fullClassName = method.getDeclClassType().getFullyQualifiedName();
            for (CallGraph.Call call : cg.callsFrom(method)) {
                MethodSignature target = call.getTargetMethodSignature();
                if (isThirdPartyMethod(target, packageMapPath)) {
                    // Use full signature with parameters to handle method overloading
                    String thirdPartyMethod = target.getDeclClassType().getFullyQualifiedName() + "."
                            + target.getName()
                            + "(" + target.getParameterTypes().stream()
                            .map(sootup.core.types.Type::toString)
                            .collect(java.util.stream.Collectors.joining(", ")) + ")";
                    CoverageFilter.registerTargetCall(fullClassName, thirdPartyMethod);
                    // Track all unique third-party call pairs
                    allThirdPartyPairs.add(Map.entry(method, target));
                }
            }
        }
        log.info("Total unique third-party method call pairs in the call graph: {}", allThirdPartyPairs.size());
        // If a class only has one unique call to a third party method, we get coverage in the simple way by only
        // analysing the html files. If it has multiple calls to the same third party method, we need to analyze the
        // xml files.
        Set<Map.Entry<MethodSignature, MethodSignature>> thirdPartyPairs = new HashSet<>();
        for (MethodSignature method : cg.getMethodSignatures()) {
            for (CallGraph.Call call : cg.callsFrom(method)) {
                MethodSignature target = call.getTargetMethodSignature();
                if (isThirdPartyMethod(target, packageMapPath)) {
                    if (CoverageFilter.isAlreadyCoveredByTests(method, target, jacocoHtmlDirs, enableAnalysisLogs)) {
                        continue;
                    }
                    if (target.getName().equals("iterator")) {
                        log.warn("Skipping iterator method {} in class {}", target, target.getDeclClassType().getFullyQualifiedName());
                        continue;
                    }
                    thirdPartyPairs.add(Map.entry(method, target));
                }
            }
        }
        return thirdPartyPairs;
    }

    /**
     * Build a reverse call graph: maps each method to all methods that call it
     */
    private static Map<MethodSignature, Set<MethodSignature>> buildReverseCallGraph(CallGraph cg) {
        Map<MethodSignature, Set<MethodSignature>> reverseGraph = new HashMap<>();
        // We could have used the callsTo method here. Just don't wanna touch it when it works.
        for (MethodSignature caller : cg.getMethodSignatures()) {
            for (CallGraph.Call call : cg.callsFrom(caller)) {
                MethodSignature callee = call.getTargetMethodSignature();
                reverseGraph.computeIfAbsent(callee, k -> new HashSet<>()).add(caller);
            }
        }
        return reverseGraph;
    }

    /**
     * Find all shortest paths from the direct caller to public methods (entry points)
     * by traversing backwards through the call graph using BFS.
     * Returns the complete paths (in reverse order: from directCaller to publicMethod).
     * Stops at the first public method found in each path (no intermediate public methods).
     */
    private static List<List<MethodSignature>> findPathsToFirstPublicCallers(
            Map<MethodSignature, Set<MethodSignature>> reverseCallGraph,
            MethodSignature directCaller,
            Set<MethodSignature> entryPoints,
            Path packageMapPath) {
        List<List<MethodSignature>> completePaths = new ArrayList<>();
        Set<MethodSignature> visited = new HashSet<>();
        Deque<List<MethodSignature>> queue = new ArrayDeque<>();
        // Start with the direct caller as the initial path
        queue.add(List.of(directCaller));
        visited.add(directCaller);
        while (!queue.isEmpty()) {
            List<MethodSignature> currentPath = queue.poll();
            MethodSignature current = currentPath.get(currentPath.size() - 1);
            // Get all methods that call the current method
            Set<MethodSignature> callers = reverseCallGraph.getOrDefault(current, Collections.emptySet());
            for (MethodSignature caller : callers) {
                if (visited.contains(caller)) {
                    continue;
                }
                // Skip third-party methods (we only want project methods in the path)
                if (isThirdPartyMethod(caller, packageMapPath)) {
                    continue;
                }
                visited.add(caller);
                // Build the new path by appending this caller
                List<MethodSignature> newPath = new ArrayList<>(currentPath);
                newPath.add(caller);
                // If this is a public method (entry point), we found a complete path
                // and stop traversing backward from it (we want first public method only)
                if (entryPoints.contains(caller)) {
                    completePaths.add(newPath);
                } else {
                    // Only continue BFS if it's not a public method
                    queue.add(newPath);
                }
            }
        }
        return completePaths;
    }

    /**
     * Find the shortest direct path from start to target where only the target is third-party.
     * Uses BFS to find the shortest path.
     */
    private static List<MethodSignature> findShortestDirectPath(
            CallGraph cg,
            MethodSignature start,
            MethodSignature target,
            Path packageMapPath) {
        Deque<List<MethodSignature>> queue = new ArrayDeque<>();
        Set<MethodSignature> visited = new HashSet<>();
        queue.add(List.of(start));
        visited.add(start);
        while (!queue.isEmpty()) {
            List<MethodSignature> path = queue.poll();
            MethodSignature last = path.get(path.size() - 1);
            for (CallGraph.Call call : cg.callsFrom(last)) {
                MethodSignature next = call.getTargetMethodSignature();
                if (next.equals(target)) {
                    // Found the target - construct and return the complete path
                    List<MethodSignature> completePath = new ArrayList<>(path);
                    completePath.add(next);
                    // Verify this is a direct path (only target is third-party).
                    // Here, we do not consider the paths that have third party methods in between.
                    if (isDirectPath(completePath, packageMapPath)) {
                        return completePath;
                    }
                }
                // Only continue if this is not a third-party method and not visited
                if (!isThirdPartyMethod(next, packageMapPath) && visited.add(next)) {
                    List<MethodSignature> newPath = new ArrayList<>(path);
                    newPath.add(next);
                    queue.add(newPath);
                }
            }
        }
        return null;
    }

    /**
     * Count all paths and their statistics from start to target without storing them.
     * Uses BFS with path length tracking to efficiently compute statistics.
     *
     * @return PathStats containing total count and length statistics, or null if no paths exist
     */
    private static PathStats countPathsAndStats(
            CallGraph cg,
            MethodSignature start,
            MethodSignature target,
            Path packageMapPath,
            int maxDepth) {
        // Queue stores: current method and current path length
        Deque<PathNode> queue = new ArrayDeque<>();
        // Simple visited set per level to avoid infinite loops within same depth
        Map<Integer, Set<MethodSignature>> visitedPerDepth = new HashMap<>();
        int totalPaths = 0;
        int shortestLength = Integer.MAX_VALUE;
        int longestLength = 0;
        queue.add(new PathNode(start, 1));
        visitedPerDepth.computeIfAbsent(1, k -> new HashSet<>()).add(start);
        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            MethodSignature currentMethod = current.method();
            int currentLength = current.pathLength();
            // Stop exploring if we have exceeded max depth (only if maxDepth is set)
            if (maxDepth > 0 && currentLength > maxDepth) {
                continue;
            }
            for (CallGraph.Call call : cg.callsFrom(currentMethod)) {
                MethodSignature next = call.getTargetMethodSignature();
                int nextLength = currentLength + 1;
                if (next.equals(target)) {
                    // Found a path to target
                    totalPaths++;
                    shortestLength = Math.min(shortestLength, nextLength);
                    longestLength = Math.max(longestLength, nextLength);
                    // Don't continue from here since we reached the target
                    continue;
                }
                // Only continue if this is not a third-party method
                // Check depth limit only if maxDepth is specified (> 0)
                if (!isThirdPartyMethod(next, packageMapPath)) {
                    boolean withinDepthLimit = (maxDepth == 0) || (nextLength <= maxDepth);
                    if (withinDepthLimit) {
                        // Check if we have already visited this method at this depth
                        Set<MethodSignature> visitedAtThisDepth = visitedPerDepth.get(nextLength);
                        if (visitedAtThisDepth == null || !visitedAtThisDepth.contains(next)) {
                            visitedPerDepth.computeIfAbsent(nextLength, k -> new HashSet<>()).add(next);
                            queue.add(new PathNode(next, nextLength));
                        }
                    }
                }
            }
        }
        if (totalPaths == 0) {
            return null;
        }
        // Use full signatures with parameters to properly identify overloaded methods
        return new PathStats(
                getFilteredMethodSignatureWithParams(start),
                getFilteredMethodSignatureWithParams(target),
                totalPaths,
                shortestLength,
                longestLength
        );
    }

    /**
     * Modified version that logs statistics instead of finding shortest path.
     * This can replace the call to findShortestDirectPath in analyzeReachability.
     */
    private static List<MethodSignature> findShortestDirectPathWithStats(
            CallGraph cg,
            MethodSignature start,
            MethodSignature target,
            Path packageMapPath,
            List<PathStats> allStats) {
        // Get statistics about all paths, Can add a depth to overcome memory problems
        PathStats stats = countPathsAndStats(cg, start, target, packageMapPath, 19);
        if (stats != null) {
            allStats.add(stats);
        }

        // Find and return the shortest direct path as before
        Deque<List<MethodSignature>> queue = new ArrayDeque<>();
        Set<MethodSignature> visited = new HashSet<>();
        queue.add(List.of(start));
        visited.add(start);
        while (!queue.isEmpty()) {
            List<MethodSignature> path = queue.poll();
            MethodSignature last = path.get(path.size() - 1);
            for (CallGraph.Call call : cg.callsFrom(last)) {
                MethodSignature next = call.getTargetMethodSignature();
                if (next.equals(target)) {
                    List<MethodSignature> completePath = new ArrayList<>(path);
                    completePath.add(next);
                    if (isDirectPath(completePath, packageMapPath)) {
                        return completePath;
                    }
                }
                if (!isThirdPartyMethod(next, packageMapPath) && visited.add(next)) {
                    List<MethodSignature> newPath = new ArrayList<>(path);
                    newPath.add(next);
                    queue.add(newPath);
                }
            }
        }
        return null;
    }

    /**
     * Get filtered method signature WITH parameter types to properly handle method overloading.
     * This should be used when we need to uniquely identify methods that may be overloaded.
     */
    public static String getFilteredMethodSignatureWithParams(MethodSignature method) {
        String className = filterName(method.getDeclClassType().getFullyQualifiedName());
        String methodName = filterName(method.getName());
        String params = method.getParameterTypes().stream()
                .map(type -> filterName(type.toString()))
                .collect(java.util.stream.Collectors.joining(", "));
        return className + "." + methodName + "(" + params + ")";
    }

    private static String filterName(String name) {
        // Replace $ followed by digit (e.g., $Array1234) with nothing
        name = name.replaceAll("\\$\\d+", "");
        // Replace $ followed by letter (e.g. Java.ArrayInitializer) with a dot
        name = name.replaceAll("\\$(?=[A-Za-z])", ".");
        return name;
    }

    public static String filterNameSimple(String name) {
        // Replace $ followed by digit (e.g., $Array1234) with nothing
        name = name.replaceAll("\\$\\d+", "");
        return name;
    }

    // Detect entry points - all public methods
    private static Set<MethodSignature> detectEntryPoints(JavaView view, String packageName) {
        return view.getClasses()
                .filter(c -> c.getType().getPackageName().getName().startsWith(packageName))
                .flatMap(c -> c.getMethods().stream())
                .filter(SootMethod::isPublic)
                .map(SootMethod::getSignature)
                .collect(Collectors.toSet());
    }

    private static boolean isThirdPartyMethod(MethodSignature method, Path packageMapPath) {
        String packageName = method.getDeclClassType().getPackageName().getName();
        // The ignored prefixes are either loaded from a txt file or are hardcoded (for basic jdk methods). The
        // package name is also added to the ignored prefixes.
        for (String ignore : ignoredPrefixes) {
            if (packageName.startsWith(ignore)) return false;
        }
        return PackageMatcher.containsPackage(packageName, packageMapPath);
    }
}
