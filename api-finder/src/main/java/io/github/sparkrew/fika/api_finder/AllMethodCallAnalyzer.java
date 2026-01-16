package io.github.sparkrew.fika.api_finder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sparkrew.fika.api_finder.utils.PackageMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.jimple.common.stmt.InvokableStmt;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Analyzes all third-party method calls in the project bytecode,
 * regardless of call graph reachability. This includes calls in Dead code, Unreachable methods, Classes that are never
 * instantiated, Private and protected methods.
 */
public class AllMethodCallAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AllMethodCallAnalyzer.class);

    /**
     * Provides a detailed breakdown of third-party calls by category.
     *
     * @param pathToJar      Path to the JAR file to analyze
     * @param packageName    The project's package name
     * @param packageMapPath Path to the package map file
     */
    public static void analyzeAndLogDetailed(String pathToJar, String packageName, Path packageMapPath) {
        Set<String> ignoredPrefixes = PackageMatcher.loadIgnoredPrefixes(packageName);
        JavaView view = createJavaView(pathToJar);
        Set<Map.Entry<MethodSignature, MethodSignature>> allCallPairs = new HashSet<>();
        Map<String, Integer> thirdPartyPackageCount = new HashMap<>();
        int totalMethods = 0;
        int methodsWithThirdPartyCalls = 0;
        for (SootClass sootClass : view.getClasses().toList()) {
            String classPackage = sootClass.getType().getPackageName().getName();
            if (!classPackage.startsWith(packageName)) {
                continue;
            }
            for (SootMethod method : sootClass.getMethods()) {
                if (!method.hasBody()) {
                    continue;
                }
                totalMethods++;
                MethodSignature callerSignature = method.getSignature();
                Set<Map.Entry<MethodSignature, MethodSignature>> methodCallPairs = new HashSet<>();
                try {
                    for (var stmt : method.getBody().getStmts()) {
                        if (stmt instanceof InvokableStmt invokableStmt) {
                            invokableStmt.getInvokeExpr().ifPresent(invokeExpr -> {
                                MethodSignature targetSignature = invokeExpr.getMethodSignature();
                                if (isThirdPartyMethod(targetSignature, ignoredPrefixes, packageMapPath)) {
                                    Map.Entry<MethodSignature, MethodSignature> pair =
                                            Map.entry(callerSignature, targetSignature);
                                    allCallPairs.add(pair);
                                    methodCallPairs.add(pair);
                                    // Track which third-party packages are being called. We don't need this, but we
                                    // keep this in case we want to write more details in the paper.
                                    String targetPackage = targetSignature.getDeclClassType()
                                            .getPackageName().getName();
                                    thirdPartyPackageCount.merge(targetPackage, 1, Integer::sum);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to analyze method body for {}: {}",
                            callerSignature, e.getMessage());
                }
                if (!methodCallPairs.isEmpty()) {
                    methodsWithThirdPartyCalls++;
                }
            }
        }
        log.info("Total methods analyzed: {}", totalMethods);
        log.info("Methods with third-party calls: {}", methodsWithThirdPartyCalls);
        log.info("Total unique third-party call pairs: {}", allCallPairs.size());
        log.info("Number of distinct third-party packages: {}", thirdPartyPackageCount.size());
        if (!thirdPartyPackageCount.isEmpty()) {
            log.info("Top third-party packages being called:");
            thirdPartyPackageCount.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(entry -> log.info("  {} - {} unique call pairs",
                            entry.getKey(), entry.getValue()));
        }
        // Write all call pairs to JSON file
        String reportPath = "all_third_party_call_pairs.json";
        writeCallPairsToJson(allCallPairs, reportPath);
    }

    private static JavaView createJavaView(String pathToJar) {
        AnalysisInputLocation inputLocation = new JavaClassPathAnalysisInputLocation(pathToJar);
        return new JavaView(inputLocation);
    }

    /**
     * This is the same method in the MethodExtractor
     */
    private static boolean isThirdPartyMethod(MethodSignature method,
                                              Set<String> ignoredPrefixes,
                                              Path packageMapPath) {
        String packageName = method.getDeclClassType().getPackageName().getName();
        for (String ignore : ignoredPrefixes) {
            if (packageName.startsWith(ignore)) {
                return false;
            }
        }
        return PackageMatcher.containsPackage(packageName, packageMapPath);
    }
    
    /**
     * Writes all unique third-party method call pairs to a JSON file.
     * 
     * @param callPairs  Set of unique (caller, third-party method) pairs
     * @param reportPath Path prefix for the output file
     */
    private static void writeCallPairsToJson(Set<Map.Entry<MethodSignature, MethodSignature>> callPairs, 
                                            String reportPath) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            
            // Convert call pairs to a more readable format
            // Use full signatures with parameters to properly distinguish overloaded methods
            List<Map<String, String>> formattedPairs = callPairs.stream()
                .map(entry -> {
                    Map<String, String> pair = new HashMap<>();
                    pair.put("caller", MethodExtractor.getFilteredMethodSignatureWithParams(entry.getKey()));
                    pair.put("thirdPartyMethod", MethodExtractor.getFilteredMethodSignatureWithParams(entry.getValue()));
                    return pair;
                })
                .sorted(Comparator.comparing(p -> p.get("caller")))
                .collect(Collectors.toList());
            String outputPath = reportPath.replace(".json", "_all_third_party_calls.json");
            File outputFile = new File(outputPath);
            Map<String, Object> output = new HashMap<>();
            output.put("totalUniquePairs", callPairs.size());
            output.put("callPairs", formattedPairs);
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, output);
            log.info("Successfully wrote {} unique third-party call pairs to {}", 
                    callPairs.size(), outputFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to write call pairs to JSON", e);
        }
    }
}