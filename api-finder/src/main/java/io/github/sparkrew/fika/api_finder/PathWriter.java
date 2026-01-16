package io.github.sparkrew.fika.api_finder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.sparkrew.fika.api_finder.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.signatures.MethodSignature;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles writing analysis results with path tracking comments.
 */
public class PathWriter {

    private static final Logger log = LoggerFactory.getLogger(PathWriter.class);

    /**
     * Write all three output formats from the analysis result.
     */
    public static void writeAllFormats(AnalysisResult result, String basePath, String sourceRootPath, boolean enableAnalysisLogs) {
        String fullMethodsPath = basePath.replace(".json", "_full_methods.json");
        writeFullMethodsFormat(result, fullMethodsPath, sourceRootPath, enableAnalysisLogs);
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
     * Write paths with full method bodies for all methods.
     * Enhanced to add tracking comments along the path.
     */
    private static void writeFullMethodsFormat(AnalysisResult result, String outputPath, String sourceRootPath,
                                               boolean enableAnalysisLogs) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        try {
            List<FullMethodsPathData> fullMethodsPaths = new ArrayList<>();
            List<FullMethodsPathData> skippedPaths = new ArrayList<>();
            for (ThirdPartyPath tp : result.thirdPartyPaths()) {
                List<String> fullMethods = extractFullMethodBodiesWithComments(tp.path(), sourceRootPath);
                ClassMemberData classMembers =
                        SourceCodeExtractor.extractClassMembers(tp.entryPoint(), sourceRootPath);
                Set<String> importsSet = SourceCodeExtractor.extractRequiredImports(
                        tp.entryPoint(), tp.path(), sourceRootPath);
                // Merge imports from class members (constructors, setters) with path imports
                importsSet.addAll(classMembers.imports());
                List<String> imports = new ArrayList<>(importsSet);
                Collections.sort(imports);
                // This is for the test template generation.  This would be another prompt format if needed.
                String testTemplate = TestTemplateGenerator.generateTestTemplate(tp);
                int conditionCount = RecordCounter.countConditionsInPath(tp.path(), sourceRootPath);
                log.debug("Path to {} has {} conditions",
                        MethodExtractor.getFilteredMethodSignatureWithParams(tp.thirdPartyMethod()),
                        conditionCount);
                // Use full signatures with parameters to properly distinguish overloaded methods
                List<String> pathStrings = tp.path().stream()
                        .map(MethodExtractor::getFilteredMethodSignatureWithParams)
                        .collect(Collectors.toList());
                // Direct caller is the second-to-last method in the path (before the third party method)
                String directCaller = pathStrings.size() >= 2 ? 
                        pathStrings.get(pathStrings.size() - 2) : 
                        pathStrings.get(0);
                FullMethodsPathData data = new FullMethodsPathData(
                        MethodExtractor.getFilteredMethodSignatureWithParams(tp.entryPoint()),
                        MethodExtractor.getFilteredMethodSignatureWithParams(tp.thirdPartyMethod()),
                        directCaller,
                        pathStrings,
                        fullMethods,
                        classMembers.constructors(),
                        classMembers.fieldDeclarations(),
                        classMembers.setters(),
                        imports,
                        testTemplate,
                        conditionCount,
                        tp.callCount(),
                        false
                );
                // We don't want a record without any source code extracted. This could happen when the source code
                // could not be retrieved and returned null instead.
                // We skip all these paths, because we don't want any bias.
                if (data.methodSources().stream().noneMatch(Objects::isNull))
                    fullMethodsPaths.add(data);
                else {
                    log.debug("Skipping path from {} to {} due to missing source code.",
                            data.entryPoint(), data.thirdPartyMethod());
                    skippedPaths.add(data);
                }
            }
            // Sort paths:  primary by path length, secondary by condition count (both ascending)
            Collections.sort(fullMethodsPaths);
            log.debug("Sorted {} paths by condition count and path length", fullMethodsPaths.size());
            if (!fullMethodsPaths.isEmpty()) {
                log.debug("Simplest path has {} conditions and {} methods",
                        fullMethodsPaths.get(0).conditionCount(),
                        fullMethodsPaths.get(0).path().size());
                log.debug("Most complex path has {} conditions and {} methods",
                        fullMethodsPaths.get(fullMethodsPaths.size() - 1).conditionCount(),
                        fullMethodsPaths.get(fullMethodsPaths.size() - 1).path().size());
            }
            File outputFile = new File(outputPath);
            mapper.writeValue(outputFile, Map.of("fullMethodsPaths", fullMethodsPaths));
            log.info("Successfully wrote {} full methods paths to {}", fullMethodsPaths.size(),
                    outputFile.getAbsolutePath());
            // We write skipped paths to a separate file for analysis
            if (enableAnalysisLogs && !skippedPaths.isEmpty()) {
                String skippedPathsPath = outputPath.replace("_full_methods.json", "_skipped_paths.json");
                File skippedFile = new File(skippedPathsPath);
                mapper.writeValue(skippedFile, Map.of("skippedPaths", skippedPaths));
                log.info("Successfully wrote {} skipped paths to {}", skippedPaths.size(),
                        skippedFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Failed to write full methods format to JSON", e);
        }
    }

    /**
     * Extract full method bodies with path tracking comments.
     * Each method will have a comment indicating which call leads to the next method in the path.
     */
    private static List<String> extractFullMethodBodiesWithComments(List<MethodSignature> path,
                                                                    String sourceRootPath) {
        List<String> methodBodies = new ArrayList<>();
        // We do not want the bytecode of the third-party method itself
        // Comment this line if the decision is changed.
        // Remove the last element (third-party method) from the path
        List<MethodSignature> pathWithoutThirdParty = new ArrayList<>(path);
        if (!pathWithoutThirdParty.isEmpty()) {
            pathWithoutThirdParty.remove(pathWithoutThirdParty.size() - 1);
        }
        // Extract each method with comment pointing to the next method
        for (int i = 0; i < pathWithoutThirdParty.size(); i++) {
            MethodSignature currentMethod = pathWithoutThirdParty.get(i);
            // Get the next method in the path (could be from pathWithoutThirdParty or the third-party method)
            MethodSignature nextMethod = (i + 1 < path.size()) ? path.get(i + 1) : null;
            String body = extractMethodBodyWithComment(currentMethod, nextMethod, sourceRootPath);
            methodBodies.add(body);
        }
        return methodBodies;
    }

    /**
     * Extract the body of a single method with a tracking comment for the next method in the path.
     */
    private static String extractMethodBodyWithComment(MethodSignature methodSig,
                                                       MethodSignature nextMethodSig,
                                                       String sourceRootPath) {
        // If source root is provided, extract from source code with path tracking
        if (sourceRootPath != null) {
            String sourceCode = SourceCodeExtractor.extractMethodFromSource(methodSig, sourceRootPath, nextMethodSig);
            if (sourceCode != null) {
                return sourceCode;
            }
            log.debug("Could not extract source for {}, falling back to null", methodSig);
        }
        // Return null instead of bytecode
        return null;
    }
}
