package io.github.sparkrew.fika.api_finder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sparkrew.fika.api_finder.model.AnalysisResult;
import io.github.sparkrew.fika.api_finder.model.ThirdPartyPath;
import io.github.sparkrew.fika.api_finder.utils.NameFilter;
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
                               String sourceRootPath, List<File> jacocoHtmlDirs, boolean enableAnalysisLogs,
                               Path coverageJsonPath) {
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
                enableAnalysisLogs, coverageJsonPath);
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
                                                      String sourceRootPath, boolean enableAnalysisLogs,
                                                      Path coverageJsonPath) {
        List<ThirdPartyPath> thirdPartyPaths = new ArrayList<>();
        try {
            CallGraphAlgorithm cha = new ClassHierarchyAnalysisAlgorithm(view);
            CallGraph cg = cha.initialize(new ArrayList<>(entryPoints));
            // Identify all third-party methods that are actually called in the codebase. We go backwards from
            // third-party methods to public methods to find all paths. This is because we expect this would be more
            // efficient than doing it the other way round, as there are usually much fewer third-party methods than
            // public methods.
            Set<Map.Entry<MethodSignature, MethodSignature>> thirdPartyPairs =
                    findAllThirdPartyMethodPairs(cg, packageMapPath, jacocoHtmlDirs, enableAnalysisLogs,
                            coverageJsonPath);
            log.info("Found {} third-party method call pairs in call graph", thirdPartyPairs.size());
            // Build reverse call graph for efficient backward traversal. Otherwise, it takes painfully long time to
            // run with the forward graph (from public methods to third party methods).
            Map<MethodSignature, Set<MethodSignature>> reverseCallGraph = buildReverseCallGraph(cg);
            log.info("Using random path selection with seed: {}", RANDOM_SEED);
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
                List<List<MethodSignature>> candidatePaths = new ArrayList<>();
                // If the direct caller is already a public entry point,
                // the length-2 path is one candidate
                if (entryPoints.contains(directCaller)) {
                    candidatePaths.add(List.of(directCaller));
                }
                // Also search for longer paths through other callers of directCaller
                List<List<MethodSignature>> longerPaths = findPathsToFirstPublicCallers(
                        reverseCallGraph,
                        directCaller,
                        entryPoints,
                        packageMapPath
                );
                candidatePaths.addAll(longerPaths);

                if (!candidatePaths.isEmpty()) {
                    // Randomly pick one path from all candidates
                    List<MethodSignature> chosenReversePath = candidatePaths.get(
                            RANDOM.nextInt(candidatePaths.size()));
                    List<MethodSignature> completePath = new ArrayList<>(chosenReversePath);
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
            log.info("Collected " + thirdPartyPaths.size() + " third-party paths.");
        } catch (Exception e) {
            log.error("Failed to initialize call graph.", e);
        }
        return new AnalysisResult(thirdPartyPaths);
    }

    /**
     * Find all third-party method call pairs (caller -> third-party method) in the call graph
     */
    private static Set<Map.Entry<MethodSignature, MethodSignature>> findAllThirdPartyMethodPairs(
            CallGraph cg, Path packageMapPath, List<File> jacocoHtmlDirs, boolean enableAnalysisLogs,
            Path coverageJsonPath) {
        if (coverageJsonPath != null) {
            return findThirdPartyMethodPairsFromCoverageJson(cg, packageMapPath, coverageJsonPath);
        }
        // We do two iterations because we get coverage check in two steps. First, we register all to check if
        // the same class has multiple calls to the same third party method.
        Set<Map.Entry<MethodSignature, MethodSignature>> allThirdPartyPairs = new HashSet<>();
        for (MethodSignature method : cg.getMethodSignatures()) {
            // Skip if the caller itself is a third-party method
            if (isThirdPartyMethod(method, packageMapPath)) {
                continue;
            }
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
        log.info("Total unique third-party method call pairs in the call graph (public or non-public): {}",
                allThirdPartyPairs.size());
        // If a class only has one unique call to a third party method, we get coverage in the simple way by only
        // analysing the html files. If it has multiple calls to the same third party method, we need to analyze the
        // xml files.
        Set<Map.Entry<MethodSignature, MethodSignature>> thirdPartyPairs = new HashSet<>();
        Set<Map.Entry<MethodSignature, MethodSignature>> skippedDueToCov = new HashSet<>();
        for (MethodSignature method : cg.getMethodSignatures()) {
            // Skip if the caller itself is a third-party method
            if (isThirdPartyMethod(method, packageMapPath)) {
                continue;
            }
            for (CallGraph.Call call : cg.callsFrom(method)) {
                MethodSignature target = call.getTargetMethodSignature();
                if (isThirdPartyMethod(target, packageMapPath)) {
                    if (CoverageFilter.isAlreadyCoveredByTests(method, target, jacocoHtmlDirs, enableAnalysisLogs)) {
                        skippedDueToCov.add(Map.entry(method, target));
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
        log.info("Unique third-party method call pairs after coverage filtering: {}", thirdPartyPairs.size());
        log.info("Skipped {} third-party method call pairs due to coverage", skippedDueToCov.size());
        return thirdPartyPairs;
    }

    private static Set<Map.Entry<MethodSignature, MethodSignature>> findThirdPartyMethodPairsFromCoverageJson(
            CallGraph cg, Path packageMapPath, Path coverageJsonPath) {
        Set<String> uncoveredPairKeys = loadUncoveredPairsFromCoverageJson(coverageJsonPath);
        log.info("Loaded {} uncovered call pairs from {}", uncoveredPairKeys.size(), coverageJsonPath);
        Set<Map.Entry<MethodSignature, MethodSignature>> thirdPartyPairs = new HashSet<>();
        for (MethodSignature method : cg.getMethodSignatures()) {
            if (isThirdPartyMethod(method, packageMapPath)) {
                continue;
            }
            for (CallGraph.Call call : cg.callsFrom(method)) {
                MethodSignature target = call.getTargetMethodSignature();
                if (isThirdPartyMethod(target, packageMapPath)) {
                    String callerKey = toCoverageJsonCallerKey(method);
                    String targetKey = toCoverageJsonTargetKey(target);
                    String pairKey = callerKey + "->" + targetKey;
                    if (uncoveredPairKeys.contains(pairKey)) {
                        thirdPartyPairs.add(Map.entry(method, target));
                    }
                }
            }
        }
        log.info("Matched {} third-party method call pairs from coverage.json", thirdPartyPairs.size());
        return thirdPartyPairs;
    }

    private static Set<String> loadUncoveredPairsFromCoverageJson(Path coverageJsonPath) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> entries = mapper.readValue(
                    coverageJsonPath.toFile(), new TypeReference<>() {});
            Set<String> uncoveredPairs = new HashSet<>();
            for (Map<String, Object> entry : entries) {
                boolean covered = Boolean.TRUE.equals(entry.get("covered"));
                if (!covered) {
                    String caller = (String) entry.get("caller");
                    String thirdPartyMethod = (String) entry.get("thirdPartyMethod");
                    uncoveredPairs.add(caller + "->" + thirdPartyMethod);
                }
            }
            return uncoveredPairs;
        } catch (Exception e) {
            log.error("Failed to load coverage.json from {}", coverageJsonPath, e);
            return Collections.emptySet();
        }
    }

    private static String toCoverageJsonCallerKey(MethodSignature method) {
        return NameFilter.filterName(method.getDeclClassType().getFullyQualifiedName())
                + "." + method.getName()
                + "(" + method.getParameterTypes().stream()
                .map(sootup.core.types.Type::toString)
                .collect(Collectors.joining(", ")) + ")";
    }

    private static String toCoverageJsonTargetKey(MethodSignature target) {
        return NameFilter.filterName(target.getDeclClassType().getFullyQualifiedName())
                + "." + target.getName()
                + "(" + target.getParameterTypes().stream()
                .map(sootup.core.types.Type::toString)
                .collect(Collectors.joining(", ")) + ")";
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

    private static final long RANDOM_SEED = 42;
    private static final Random RANDOM = new Random(RANDOM_SEED);

    /**
     * Find a single random path from the direct caller to a public method (entry point)
     * by doing a random walk backwards through the call graph.
     *
     * Unlike BFS (which always finds the shortest path) or naive DFS (which stops at
     * the first entry point — usually equally short, since public methods are dense),
     * this walks past intermediate entry points to discover paths of varying lengths,
     * then randomly selects one of the entry points encountered along the walk.
     */
    private static List<List<MethodSignature>> findPathsToFirstPublicCallers(
            Map<MethodSignature, Set<MethodSignature>> reverseCallGraph,
            MethodSignature directCaller,
            Set<MethodSignature> entryPoints,
            Path packageMapPath) {
        // Random walk: build a path from directCaller backwards, not stopping at entry points
        List<MethodSignature> walkPath = new ArrayList<>();
        walkPath.add(directCaller);
        Set<MethodSignature> onPath = new HashSet<>();
        onPath.add(directCaller);

        MethodSignature current = directCaller;
        while (true) {
            List<MethodSignature> callers = new ArrayList<>(
                    reverseCallGraph.getOrDefault(current, Collections.emptySet()));
            // Filter out nodes already on the path (cycles) and third-party methods
            callers.removeIf(c -> onPath.contains(c) || isThirdPartyMethod(c, packageMapPath));
            if (callers.isEmpty()) {
                break;
            }
            MethodSignature next = callers.get(RANDOM.nextInt(callers.size()));
            walkPath.add(next);
            onPath.add(next);
            current = next;
        }

        // Collect indices of all entry points found along the walk (excluding index 0
        // which is directCaller — that case is handled by the caller)
        List<Integer> entryPointIndices = new ArrayList<>();
        for (int i = 1; i < walkPath.size(); i++) {
            if (entryPoints.contains(walkPath.get(i))) {
                entryPointIndices.add(i);
            }
        }

        if (entryPointIndices.isEmpty()) {
            return Collections.emptyList();
        }

        // Randomly pick one of the entry points found along the walk
        int chosenIdx = entryPointIndices.get(RANDOM.nextInt(entryPointIndices.size()));
        // Return the sub-path from directCaller up to (and including) the chosen entry point
        return List.of(new ArrayList<>(walkPath.subList(0, chosenIdx + 1)));
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
