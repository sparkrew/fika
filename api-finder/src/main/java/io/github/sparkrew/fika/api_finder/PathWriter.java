package io.github.sparkrew.fika.api_finder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.chains_project.fika.api_finder.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles writing analysis results in three different formats:
 * Full methods - complete implementation of all methods in paths
 * Entry point only - only the entry point body (public method under test) with path info
 * Method slices - relevant code slices for reaching third-party methods
 */
public class PathWriter {

    private static final Logger log = LoggerFactory.getLogger(PathWriter.class);

    /**
     * Write all three output formats from the analysis result.
     */
    public static void writeAllFormats(AnalysisResult result, String basePath, JavaView view, String sourceRootPath) {
        // Generate the three output file paths based on the base path
        String fullMethodsPath = basePath.replace(".json", "_full_methods.json");
        // Full methods for all methods in the path. Gives complete implementation details.
        writeFullMethodsFormat(result, fullMethodsPath, view, sourceRootPath);
    }

    /**
     * Write path statistics (to justify the decision to select the shortest path) to a JSON file for analysis
     */
    public static void writePathStatsToJson(List<PathStats> stats) {
        String statsPath = "path-stats.json";
        try (FileWriter writer = new FileWriter(statsPath)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(stats, writer);
            log.info("Path statistics written to: {}", statsPath);
        } catch (IOException e) {
            log.error("Failed to write path statistics to JSON", e);
        }
    }

    /**
     * Write paths with full method bodies for all methods
     * This gives the complete implementation of every method in the path
     */
    private static void writeFullMethodsFormat(AnalysisResult result, String outputPath, JavaView view, String sourceRootPath) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        try {
            List<FullMethodsPathData> fullMethodsPaths = new ArrayList<>();
            for (ThirdPartyPath tp : result.thirdPartyPaths()) {
                List<String> fullMethods = extractFullMethodBodies(view, tp.path(), sourceRootPath);
                ClassMemberData classMembers =
                        SourceCodeExtractor.extractClassMembers(tp.entryPoint(), sourceRootPath);
                Set<String> importsSet = SourceCodeExtractor.extractRequiredImports(
                        tp.entryPoint(), tp.path(), sourceRootPath);
                List<String> imports = new ArrayList<>(importsSet);
                Collections.sort(imports);
                // This is for the test template generation.  This would be another prompt format if needed.
                String testTemplate = TestTemplateGenerator.generateTestTemplate(tp, view);
                // Count conditions in the path
                int conditionCount = RecordCounter.countConditionsInPath(tp.path(), sourceRootPath);
                log.debug("Path to {} has {} conditions",
                        MethodExtractor.getFilteredMethodSignature(tp.thirdPartyMethod()),
                        conditionCount);
                // Build the path as strings
                List<String> pathStrings = tp.path().stream()
                        .map(MethodExtractor:: getFilteredMethodSignature)
                        .collect(Collectors.toList());
                FullMethodsPathData data = new FullMethodsPathData(
                        MethodExtractor.getFilteredMethodSignature(tp.entryPoint()),
                        MethodExtractor.getFilteredMethodSignature(tp.thirdPartyMethod()),
                        pathStrings,
                        fullMethods,
                        classMembers.constructors(),
                        classMembers.setters(),
                        classMembers.getters(),
                        imports,
                        testTemplate,
                        conditionCount
                );
                // We don't want a record without any source code extracted. This could happen when the source code
                // could not be retrieved and returned null instead.
                // We skip all these paths, because we don't want any bias.
                if (data.methodSources().stream().noneMatch(Objects::isNull))
                    fullMethodsPaths.add(data);
            }
            // Sort paths:  primary by condition count, secondary by path length (both ascending)
            Collections.sort(fullMethodsPaths);
            log.info("Sorted {} paths by condition count and path length", fullMethodsPaths.size());
            if (! fullMethodsPaths.isEmpty()) {
                log.info("Simplest path has {} conditions and {} methods",
                        fullMethodsPaths.get(0).conditionCount(),
                        fullMethodsPaths.get(0).path().size());
                log.info("Most complex path has {} conditions and {} methods",
                        fullMethodsPaths.get(fullMethodsPaths.size() - 1).conditionCount(),
                        fullMethodsPaths.get(fullMethodsPaths.size() - 1).path().size());
            }
            File outputFile = new File(outputPath);
            mapper.writeValue(outputFile, Map.of("fullMethodsPaths", fullMethodsPaths));
            log.info("Successfully wrote {} full methods paths to {}", fullMethodsPaths.size(),
                    outputFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to write full methods format to JSON", e);
        }
    }

    /**
     * Extract full method bodies for all methods in a path.
     * This gives the complete implementation of each method.
     */
    private static List<String> extractFullMethodBodies(JavaView view, List<MethodSignature> path, String sourceRootPath) {
        List<String> methodBodies = new ArrayList<>();
        // We do not want the bytecode of the third-party method itself
        // Comment this line if the decision is changed.
        path.remove(path.size() - 1);
        for (MethodSignature methodSig : path) {
            String body = extractMethodBody(view, methodSig, sourceRootPath);
            methodBodies.add(body);
        }
        return methodBodies;
    }

    /**
     * Extract the body of a single method.
     * If sourceRootPath is provided, reads from actual Java source files.
     * Otherwise, falls back to Jimple IR from the JAR.
     */
    private static String extractMethodBody(JavaView view, MethodSignature methodSig, String sourceRootPath) {
        // If source root is provided, try to extract from source code using Spoon
        if (sourceRootPath != null) {
            String sourceCode = SourceCodeExtractor.extractMethodFromSource(methodSig, sourceRootPath);
            if (sourceCode != null) {
                return sourceCode;
            }
            // If source extraction failed, fall through to Jimple extraction
            log.debug("Could not extract source for {}, falling back to Jimple", methodSig);
        }
        // Fall back to Jimple IR extraction
        // We decided to keep this bytecode fallback because we already implemented and did not want to go that effort
        // to waste. Also, in some cases, source code may not be available (e.g., third-party methods). If we decided
        // to add bytecode of third-party methods in the future, this would be useful.
        // return extractMethodBodyFromJimple(view, methodSig);

        // We return null now because we don't want to deal with bytecode.
        return null;
    }

    /**
     * Extract method body from Jimple IR (bytecode representation).
     * Used as fallback when source code is not available.
     */
    private static String extractMethodBodyFromJimple(JavaView view, MethodSignature methodSig) {
        try {
            Optional<JavaSootMethod> methodOpt = view.getMethod(methodSig);
            if (methodOpt.isPresent()) {
                SootMethod method = methodOpt.get();
                // Get method body if available (this is the Jimple IR representation)
                if (method.hasBody()) {
                    return method.getBody().toString();
                } else {
                    // If no body available (e.g., third-party, interface, or abstract method)
                    return "// Method body not available for: " +
                            MethodExtractor.getFilteredMethodSignature(methodSig);
                }
            } else {
                return "// Method not found in view: " +
                        MethodExtractor.getFilteredMethodSignature(methodSig);
            }
        } catch (Exception e) {
            log.warn("Failed to extract method body for {}: {}", methodSig, e.getMessage());
            return "// Error extracting method body: " + e.getMessage();
        }
    }
}
