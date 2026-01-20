package io.github.sparkrew.fika.api_finder;

import io.github.sparkrew.fika.api_finder.utils.NameFilter;
import io.github.sparkrew.fika.api_finder.utils.SpoonMethodFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.signatures.MethodSignature;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts control flow conditions (if, for, while, switch, do-while) in methods.
 * This helps prioritize simpler paths with fewer conditional branches.
 */
public class RecordCounter {

    private static final Logger log = LoggerFactory.getLogger(RecordCounter.class);
    // Cache for method condition counts to avoid re-parsing
    private static final Map<String, Integer> conditionCache = new HashMap<>();

    /**
     * Count total conditions across all methods in a path.
     *
     * @param path           List of method signatures in the path
     * @param sourceRootPath Root directory of source code
     * @return Total count of control flow conditions
     */
    public static int countConditionsInPath(List<MethodSignature> path, String sourceRootPath) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        int totalConditions = 0;
        // Count conditions in each method (excluding the last third-party method)
        for (MethodSignature methodSig : path) {
            int methodConditions = countConditionsInMethod(methodSig, sourceRootPath);
            totalConditions += methodConditions;
            log.trace("Method {} has {} conditions",
                    NameFilter.getFilteredMethodSignatureWithParams(methodSig), methodConditions);
        }
        log.debug("Path has total {} conditions across {} methods", totalConditions, path.size() - 1);
        return totalConditions;
    }

    /**
     * Count conditions in a single method.
     * Uses caching to avoid re-parsing the same method multiple times.
     *
     * @param methodSig      Method signature
     * @param sourceRootPath Root directory of source code
     * @return Count of control flow conditions in the method
     */
    private static int countConditionsInMethod(MethodSignature methodSig, String sourceRootPath) {
        String cacheKey = methodSig.toString();
        // Check cache first
        if (conditionCache.containsKey(cacheKey)) {
            return conditionCache.get(cacheKey);
        }
        int count = 0;
        try {
            if (sourceRootPath == null) {
                log.debug("No source root provided, cannot count conditions for {}", cacheKey);
                conditionCache.put(cacheKey, 0);
                return 0;
            }
            CtModel spoonModel = SourceCodeExtractor.getModel(sourceRootPath);
            String className = NameFilter.filterNameSimple(methodSig.getDeclClassType().getFullyQualifiedName());
            String methodName = methodSig.getName();
            // Find the type using shared helper
            CtType<?> ctType = SpoonMethodFinder.findTypeCached(spoonModel, className);
            if (ctType == null) {
                log.debug("Type not found: {}", className);
                conditionCache.put(cacheKey, 0);
                return 0;
            }
            // Handle special method names from bytecode
            if ("<init>".equals(methodName)) {
                // <init> represents a constructor
                var constructor = SpoonMethodFinder.findConstructor(ctType, methodSig);
                count = constructor != null ? countConditionsInExecutable(constructor) : 9999;
            } else if ("<clinit>".equals(methodName)) {
                // <clinit> represents a static initializer block
                count = countConditionsInStaticInitializer(ctType);
            } else {
                // Regular method - pass methodSig for overload resolution
                var method = SpoonMethodFinder.findRegularMethod(ctType, methodName, methodSig);
                count = method != null ? countConditionsInExecutable(method) : 999;
            }
        } catch (Exception e) {
            log.warn("Error counting conditions for {}: {}", methodSig, e.getMessage());
        }
        // Cache the result
        conditionCache.put(cacheKey, count);
        return count;
    }

    /**
     * Extract static initializer block(s) from the type.
     * Static initializers are represented as <clinit> in bytecode.
     */
    private static int countConditionsInStaticInitializer(CtType<?> ctType) {
        // Get all anonymous executable blocks (static initializers)
        var staticBlocks = ctType.getElements(
                element -> element instanceof spoon.reflect.code.CtBlock &&
                        element.getParent() instanceof CtType &&
                        !element.isImplicit()
        );
        if (staticBlocks.isEmpty()) {
            // No explicit static initializer found
            log.debug("No static initializer found for {}", ctType.getQualifiedName());
            return 0;
        }
        int totalConditions = 0;
        for (var block : staticBlocks) {
            totalConditions += countConditionsInBlock((CtBlock<?>) block);
        }
        return totalConditions;
    }

    /**
     * Count all control flow conditions in an executable (method or constructor).
     * Counts:  if, for, while, do-while, switch, foreach, conditional expressions
     */
    private static int countConditionsInExecutable(spoon.reflect.declaration.CtExecutable<?> executable) {
        if (executable.getBody() == null) {
            return 0;
        }
        return countConditionsInBlock(executable.getBody());
    }

    /**
     * Count conditions in a code block.
     */
    private static int countConditionsInBlock(CtBlock<?> block) {
        int count = 0;
        // Count if statements
        List<CtIf> ifStatements = block.getElements(element -> element instanceof CtIf);
        count += ifStatements.size();
        // Count for loops
        List<CtFor> forLoops = block.getElements(element -> element instanceof CtFor);
        count += forLoops.size();
        // Count foreach loops
        List<CtForEach> forEachLoops = block.getElements(element -> element instanceof CtForEach);
        count += forEachLoops.size();
        // Count while loops
        List<CtWhile> whileLoops = block.getElements(element -> element instanceof CtWhile);
        count += whileLoops.size();
        // Count do-while loops
        List<CtDo> doWhileLoops = block.getElements(element -> element instanceof CtDo);
        count += doWhileLoops.size();
        // Count switch statements
        List<CtSwitch<?>> switchStatements = block.getElements(element -> element instanceof CtSwitch);
        count += switchStatements.size();
        // Count conditional (ternary) expressions: condition ? true : false
        List<CtConditional<?>> conditionalExpressions = block.getElements(
                element -> element instanceof CtConditional);
        count += conditionalExpressions.size();
        return count;
    }

    /**
     * Clear the condition cache.
     * Useful when processing multiple projects or for testing.
     */
    public static void clearCache() {
        conditionCache.clear();
        log.debug("Cleared condition cache");
    }

    /**
     * Get cache statistics for monitoring/debugging.
     */
    public static String getCacheStats() {
        return String.format("Condition cache: %d entries", conditionCache.size());
    }
}
