package io.github.sparkrew.fika.api_finder.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration. CtType;
import sootup.core.signatures.MethodSignature;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static io.github.sparkrew.fika.api_finder.utils.NameFilter.filterName;

/**
 * Shared utility for finding types and methods in the Spoon model.
 * Eliminates code duplication between SourceCodeExtractor and ConditionCounter.
 */
public class SpoonMethodFinder {

    private static final Logger log = LoggerFactory.getLogger(SpoonMethodFinder.class);
    // Type cache:  maps class name to CtType for faster lookups
    private static final Map<String, CtType<?>> typeCache = new HashMap<>();

    /**
     * Find a type with caching to speed up repeated lookups.
     */
    public static CtType<?> findTypeCached(CtModel spoonModel, String fullyQualifiedName) {
        // Check cache first
        if (typeCache.containsKey(fullyQualifiedName)) {
            return typeCache.get(fullyQualifiedName);
        }
        // Not in cache, do the lookup
        CtType<?> type = findType(spoonModel, fullyQualifiedName);
        // Cache the result (even if null)
        typeCache.put(fullyQualifiedName, type);
        return type;
    }

    /**
     * Find a type in the Spoon model by its fully qualified name.
     * Handles both regular classes and inner classes.
     */
    public static CtType<?> findType(CtModel spoonModel, String fullyQualifiedName) {
        // Try direct lookup first
        CtType<?> type = spoonModel.getAllTypes().stream()
                .filter(t -> t.getQualifiedName().equals(fullyQualifiedName))
                .findFirst()
                .orElse(null);
        if (type != null) {
            return type;
        }
        String spoonName = filterName(fullyQualifiedName);
        type = spoonModel.getAllTypes().stream()
                .filter(t -> t.getQualifiedName().equals(spoonName))
                .findFirst()
                .orElse(null);
        if (type != null) {
            return type;
        }
        // Try looking for the outer class and then finding the inner class.
        if (fullyQualifiedName.contains("$")) {
            String outerClassName = fullyQualifiedName.substring(0, fullyQualifiedName.indexOf("$"));
            CtType<?> outerType = findTypeCached(spoonModel, outerClassName);
            if (outerType != null) {
                String innerClassName = fullyQualifiedName.substring(fullyQualifiedName.lastIndexOf("$") + 1);
                return outerType.getNestedType(innerClassName);
            }
        }
        return null;
    }

    /**
     * Find a regular method by name and signature.
     * Handles method overloading by matching the full signature.
     * If the method is not found in the current type, searches in the superclass hierarchy.
     */
    public static CtMethod<?> findRegularMethod(CtType<?> ctType, String methodName, MethodSignature methodSig) {
        // Get all methods with the matching name
        var candidateMethods = ctType.getMethods().stream()
                .filter(m -> m.getSimpleName().equals(methodName))
                .toList();
        if (candidateMethods.isEmpty()) {
            return null;
        }
        // If there's only one method with this name, we are lucky, just return it
        if (candidateMethods.size() == 1) {
            return candidateMethods.get(0);
        }
        // Multiple methods with same name - not so lucky this time, need to match by parameter types,
        // why?  bcoz of overloading
        int paramCount = methodSig.getParameterTypes().size();
        var paramTypes = methodSig.getParameterTypes();
        // Try to find exact match by parameter count and types
        Optional<CtMethod<?>> exactMatch = candidateMethods.stream()
                .filter(m -> m.getParameters().size() == paramCount)
                .filter(m -> parametersMatch(m, paramTypes))
                .findFirst();
        if (exactMatch.isPresent()) {
            return exactMatch.get();
        }
        // We are unlucky, Fall back to matching by parameter count only.
        Optional<CtMethod<?>> countMatch = candidateMethods.stream()
                .filter(m -> m.getParameters().size() == paramCount)
                .findFirst();
        if (countMatch.isPresent()) {
            log.debug("Multiple overloaded methods found for {}, matched by parameter count", methodName);
            return countMatch.get();
        }
        // We are really unlucky!  After all this effort, we just have to return the first method.
        log.warn("Multiple overloaded methods found for {}, returning first one", methodName);
        return candidateMethods.get(0);
    }

    /**
     * Find a constructor by signature.
     * If there are multiple constructors, tries to match by parameter count.
     * Otherwise returns the first constructor.
     */
    public static CtConstructor<? > findConstructor(CtType<?> ctType, MethodSignature methodSig) {
        // Get all constructors
        var constructors = ctType.getElements(
                element -> element instanceof spoon.reflect.declaration.CtConstructor
        );
        if (constructors.isEmpty()) {
            return null;
        }
        // If there's only one constructor, return it
        if (constructors.size() == 1) {
            return (CtConstructor<?>) constructors.get(0);
        }
        // Try to match by parameter count
        int paramCount = methodSig.getParameterTypes().size();
        Optional<? extends CtConstructor<?>> matchingConstructor =
                constructors.stream()
                        .map(c -> (CtConstructor<?>) c)
                        .filter(c -> c.getParameters().size() == paramCount)
                        .findFirst();
        if (matchingConstructor.isPresent()) {
            // Get the pretty-printed source code for the matching constructor, toString does not give proper source
            return matchingConstructor. get();
        }
        // Screw it, we give up, fall back to first constructor
        log.warn("Multiple constructors found, returning first one for {}", ctType.getQualifiedName());
        return (CtConstructor<?>) constructors.get(0);
    }

    /**
     * Check if a Spoon method's parameters match the SootUp method signature's parameter types.
     * Compares type names (simple or qualified) to handle overloading.
     */
    public static boolean parametersMatch(CtMethod<?> spoonMethod,
                                          java.util.List<sootup.core.types.Type> sootParams) {
        var spoonParams = spoonMethod.getParameters();
        if (spoonParams.size() != sootParams.size()) {
            return false;
        }
        // Compare each parameter type
        for (int i = 0; i < spoonParams.size(); i++) {
            var spoonParam = spoonParams.get(i);
            var sootParam = sootParams.get(i);
            String spoonTypeName = spoonParam.getType().getQualifiedName();
            String sootTypeName = sootParam.toString();
            // Try to match by simple name or qualified name
            if (! typesMatch(spoonTypeName, sootTypeName)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if two type names match, handling both simple and qualified names.
     * For example:  "String" matches "java.lang.String", "int" matches "int"
     */
    public static boolean typesMatch(String spoonTypeName, String sootTypeName) {
        // Direct match
        if (spoonTypeName.equals(sootTypeName)) {
            return true;
        }
        // Try matching simple names (last part after dot)
        String spoonSimple = spoonTypeName.contains(".") ?
                spoonTypeName.substring(spoonTypeName.lastIndexOf(". ") + 1) :
                spoonTypeName;
        String sootSimple = sootTypeName.contains(".") ?
                sootTypeName.substring(sootTypeName.lastIndexOf(". ") + 1) :
                sootTypeName;
        if (spoonSimple.equals(sootSimple)) {
            return true;
        }
        // Handle array types
        if (spoonTypeName.endsWith("[]") && sootTypeName.endsWith("[]")) {
            String spoonBase = spoonTypeName.substring(0, spoonTypeName.length() - 2);
            String sootBase = sootTypeName.substring(0, sootTypeName.length() - 2);
            return typesMatch(spoonBase, sootBase);
        }
        return false;
    }

    /**
     * Clear the type cache.
     * Useful for testing or when processing multiple projects.
     */
    public static void clearCache() {
        typeCache.clear();
        log.debug("Cleared type cache");
    }

    /**
     * Get cache statistics for monitoring/debugging.
     */
    public static String getCacheStats() {
        return String.format("Type cache: %d entries", typeCache.size());
    }
}
