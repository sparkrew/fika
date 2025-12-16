package io.github.sparkrew.fika.api_finder;

import io.github.sparkrew.fika.api_finder.model.AnalysisResult;
import io.github.sparkrew.fika.api_finder.model.ThirdPartyPath;
import io.github.sparkrew.fika.api_finder.utils.PackageMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.callgraph.CallGraph;
import sootup.callgraph.RapidTypeAnalysisAlgorithm;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static io.github.sparkrew.fika.api_finder.CoverageFilter.isAlreadyCoveredByTests;

public class RandomMethodExtractor {

    static final Logger log = LoggerFactory.getLogger(MethodExtractor.class);
    static Set<String> ignoredPrefixes;

    /***************************************************************************
     * ToDo: Get the latest version of process from the original MethodExtractor,
     ***************************************************************************/


    private static JavaView createJavaView(String pathToJar) {
        AnalysisInputLocation inputLocation = new JavaClassPathAnalysisInputLocation(pathToJar);
        return new JavaView(inputLocation);
    }

    private static AnalysisResult analyzeReachability(JavaView view, Set<MethodSignature> entryPoints,
                                                      Path packageMapPath, List<File> jacocoHtmlDirs) {
        List<ThirdPartyPath> thirdPartyPaths = new ArrayList<>();
        try {
            RapidTypeAnalysisAlgorithm cha = new RapidTypeAnalysisAlgorithm(view);
            CallGraph cg = cha.initialize(new ArrayList<>(entryPoints));
            // Identify all third-party methods that are actually called in the codebase. We go backwards from
            // third-party methods to public methods to find all paths. This is because we expect this would be more
            // efficient than doing it the other way round, as there are usually much fewer third-party methods than
            // public methods.
            Set<MethodSignature> allThirdPartyMethods = findAllThirdPartyMethods(cg, packageMapPath, jacocoHtmlDirs);
            log.info("Found {} third-party methods in call graph", allThirdPartyMethods.size());
            // Build reverse call graph for efficient backward traversal. Otherwise, it takes painfully long time to
            // run with the forward graph (from public methods to third party methods).
            Map<MethodSignature, Set<MethodSignature>> reverseCallGraph = buildReverseCallGraph(cg);
            // For each third-party method, find all public methods that can reach it
            for (MethodSignature thirdPartyMethod : allThirdPartyMethods) {
                // Find paths from this third-party method to entry points (public methods) by traversing backwards.
                // This finds the path during backward traversal instead of doing a separate forward search.
                // We still collect multiple paths to reach a third party method, as long as they originate from
                // different public methods.
                List<ThirdPartyPath> pathsForThisMethod = findPathsToEntryPoints(
                        reverseCallGraph,
                        thirdPartyMethod,
                        entryPoints,
                        packageMapPath
                );
                thirdPartyPaths.addAll(pathsForThisMethod);
            }
        } catch (Exception e) {
            log.error("Failed to initialize call graph.", e);
        }
        return new AnalysisResult(thirdPartyPaths);
    }

    /**
     * Find paths from a third-party method to entry points (public methods) by traversing backwards.
     * This tracks the complete path during traversal, eliminating the need for a separate forward search.
     * Returns one path for each (public method -> third-party method) pair.
     */
    private static List<ThirdPartyPath> findPathsToEntryPoints(
            Map<MethodSignature, Set<MethodSignature>> reverseCallGraph,
            MethodSignature thirdPartyMethod,
            Set<MethodSignature> entryPoints,
            Path packageMapPath) {
        List<ThirdPartyPath> paths = new ArrayList<>();
        // Queue stores: current method and the path taken to reach it (in reverse order: third-party -> ... -> public)
        Deque<PathNode> queue = new ArrayDeque<>();
        // Track visited methods to avoid cycles
        Set<MethodSignature> visited = new HashSet<>();
        // Start from the third-party method
        queue.add(new PathNode(thirdPartyMethod, new ArrayList<>(List.of(thirdPartyMethod))));
        visited.add(thirdPartyMethod);
        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            MethodSignature currentMethod = current.method;
            List<MethodSignature> currentPath = current.path;
            // Get all methods that call the current method
            Set<MethodSignature> callers = reverseCallGraph.getOrDefault(currentMethod, Collections.emptySet());
            for (MethodSignature caller : callers) {
                if (visited.contains(caller)) {
                    continue;
                }
                // Skip third-party methods (we only want project methods in the path)
                if (isThirdPartyMethod(caller, packageMapPath)) {
                    continue;
                }
                visited.add(caller);
                // Create new path by adding this caller
                List<MethodSignature> newPath = new ArrayList<>(currentPath);
                newPath.add(caller);
                // If this is a public method (entry point), we found a complete path
                if (entryPoints.contains(caller)) {
                    // Reverse the path to get: public -> ... -> third-party
                    List<MethodSignature> forwardPath = new ArrayList<>(newPath);
                    Collections.reverse(forwardPath);
                    // Verify this is a direct path (only target is third-party).
                    // Here, we do not consider the paths that have third party methods in between.
                    if (isDirectPath(forwardPath, packageMapPath)) {
                        ThirdPartyPath tpPath = new ThirdPartyPath(
                                caller,  // public method (entry point)
                                thirdPartyMethod,  // third-party method
                                forwardPath  // complete path from public to third-party
                        );
                        paths.add(tpPath);
                    }
                    // Don't continue traversing beyond public methods
                } else {
                    // Continue traversing backward
                    queue.add(new PathNode(caller, newPath));
                }
            }
        }
        return paths;
    }

    /**
     * Helper class to store a method and the path taken to reach it during traversal
     */
    private static class PathNode {
        MethodSignature method;
        List<MethodSignature> path;
        PathNode(MethodSignature method, List<MethodSignature> path) {
            this.method = method;
            this.path = path;
        }
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
     * Find all third-party methods that are actually called in the call graph
     */
    private static Set<MethodSignature> findAllThirdPartyMethods(CallGraph cg, Path packageMapPath,
                                                                 List<File> jacocoHtmlDirs) {
        Set<MethodSignature> thirdPartyMethods = new HashSet<>();
        // Iterate through all calls in the call graph
        for (MethodSignature method : cg.getMethodSignatures()) {
            for (CallGraph.Call call : cg.callsFrom(method)) {
                MethodSignature target = call.getTargetMethodSignature();
                if (isThirdPartyMethod(target, packageMapPath)) {
                    if (isAlreadyCoveredByTests(method, target, jacocoHtmlDirs)) {
                        continue;
                    }
                    thirdPartyMethods.add(target);
                }
            }
        }
        return thirdPartyMethods;
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

    public static String getFilteredMethodSignature(MethodSignature method) {
        String className = filterName(method.getDeclClassType().getFullyQualifiedName());
        String methodName = filterName(method.getName());
        return className + "." + methodName;
    }

    private static String filterName(String name) {
        // Replace $ followed by digit (e.g., $Array1234) with nothing
        name = name.replaceAll("\\$\\d+", "");
        // Replace $ followed by letter (e.g. Java.ArrayInitializer) with a dot
        name = name.replaceAll("\\$(?=[A-Za-z])", ".");
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