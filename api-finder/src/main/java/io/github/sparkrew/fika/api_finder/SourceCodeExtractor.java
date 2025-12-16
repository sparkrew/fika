package io.github.sparkrew.fika.api_finder;

import io.github.sparkrew.fika.api_finder.model.ClassMemberData;
import io.github.sparkrew.fika.api_finder.utils.SpoonMethodFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.signatures.MethodSignature;
import spoon.MavenLauncher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Extracts actual source code from Java files using Spoon.
 * This reads the project's .java files to get the real source code
 * instead of the Jimple IR representation.
 * <p>
 * Uses caching at two levels:
 * Model cache: The parsed Spoon model for the source root
 * Method cache: Individual method bodies that have been extracted
 */
public class SourceCodeExtractor {

    private static final Logger log = LoggerFactory.getLogger(SourceCodeExtractor.class);
    // Method cache: maps method signature string to extracted source code
    private static final Map<String, String> methodCache = new HashMap<>();
    // Type cache: maps class name to CtType for faster lookups
    private static final Map<String, CtType<?>> typeCache = new HashMap<>();
    protected static String currentSourceRoot;
    // Model cache
    private static CtModel model;

    /**
     * Initialize or retrieve the Spoon model for the given source root.
     * This is cached to avoid re-parsing the entire source tree multiple times.
     */
    private static CtModel getOrCreateModel(String sourceRootPath) {
        // Cache the model if we are using the same source root
        if (model != null && sourceRootPath.equals(currentSourceRoot)) {
            return model;
        }
        log.info("Building Spoon model from source root: {}", sourceRootPath);
        try {
            MavenLauncher launcher = new MavenLauncher(sourceRootPath,
                    MavenLauncher.SOURCE_TYPE.APP_SOURCE);
            // Configure Spoon to be more lenient
            launcher.getEnvironment().setNoClasspath(true);
            launcher.getEnvironment().setCommentEnabled(true);
            launcher.getEnvironment().disableConsistencyChecks();
            model = launcher.buildModel();
            currentSourceRoot = sourceRootPath;
            // Clear caches when we build a new model
            methodCache.clear();
            typeCache.clear();
            log.info("Spoon model built successfully with {} types", model.getAllTypes().size());
            return model;
        } catch (Exception e) {
            log.error("Error building Spoon model: {}", e.getMessage(), e);
            model = null;
            currentSourceRoot = null;
            throw new RuntimeException("Failed to build Spoon model", e);
        }
    }

    /**
     * Get the current source root path.
     */
    public static String getCurrentSourceRoot() {
        return currentSourceRoot;
    }

    /**
     * Extract a method's source code from the actual Java source file using Spoon.
     *
     * @param methodSig      The method signature to extract
     * @param sourceRootPath The root directory of the source code
     * @return The source code of the method, or null if not found
     */
    public static String extractMethodFromSource(MethodSignature methodSig, String sourceRootPath) {
        // Create a cache key from the method signature
        String cacheKey = methodSig.toString();
        // Check if we already extracted this method
        if (methodCache.containsKey(cacheKey)) {
            log.trace("Method cache hit for {}", cacheKey);
            return methodCache.get(cacheKey);
        }
        try {
            CtModel spoonModel = getOrCreateModel(sourceRootPath);
            // Get the fully qualified class name
            String className = methodSig.getDeclClassType().getFullyQualifiedName();
            String methodName = methodSig.getName();
            // Handle inner classes - Spoon uses $ for inner classes
            CtType<?> ctType = findTypeCached(spoonModel, className);
            if (ctType == null) {
                log.debug("Type not found in Spoon model: {}", className);
                // Cache the null result to avoid repeated lookups
                methodCache.put(cacheKey, null);
                return null;
            }
            // Handle special method names from bytecode
            String sourceCode = null;
            if ("<init>".equals(methodName)) {
                // <init> represents a constructor
                sourceCode = extractConstructor(ctType, methodSig);
            } else if ("<clinit>".equals(methodName)) {
                // <clinit> represents a static initializer block
                sourceCode = extractStaticInitializer(ctType);
            } else {
                // Regular method - pass methodSig for overload resolution
                sourceCode = extractRegularMethod(ctType, methodName, methodSig);
            }
            if (sourceCode == null) {
                log.debug("Method {} not found in type {}", methodName, className);
            }
            // Cache the result (even if null)
            methodCache.put(cacheKey, sourceCode);
            if (sourceCode != null) {
                log.trace("Cached method source for {}", cacheKey);
            }
            return sourceCode;
        } catch (Exception e) {
            log.warn("Error extracting source code for {}: {}", methodSig, e.getMessage());
            // Cache the null result to avoid repeated errors
            methodCache.put(cacheKey, null);
            return null;
        }
    }

    /**
     * Extract a regular method by name and parameter types.
     * Handles method overloading by matching the full signature.
     */
    private static String extractRegularMethod(CtType<?> ctType, String methodName, MethodSignature methodSig) {
        var method = SpoonMethodFinder.findRegularMethod(ctType, methodName, methodSig);
        return method != null ? method.prettyprint() : null;
    }

    /**
     * Extract a constructor from the type.
     * If there are multiple constructors, tries to match by parameter count.
     * Otherwise returns the first constructor.
     */
    private static String extractConstructor(CtType<?> ctType, MethodSignature methodSig) {
        var constructor = SpoonMethodFinder.findConstructor(ctType, methodSig);
        return constructor != null ? constructor.prettyprint() : null;
    }

    /**
     * Find a type with caching to speed up repeated lookups.
     */
    private static CtType<?> findTypeCached(CtModel spoonModel, String fullyQualifiedName) {
        return SpoonMethodFinder.findTypeCached(spoonModel, fullyQualifiedName);
    }

    /**
     * Extract static initializer block(s) from the type.
     * Static initializers are represented as <clinit> in bytecode.
     */
    private static String extractStaticInitializer(CtType<?> ctType) {
        // Get all anonymous executable blocks (static initializers)
        var staticBlocks = ctType.getElements(
                element -> element instanceof spoon.reflect.code.CtBlock &&
                        element.getParent() instanceof CtType &&
                        !element.isImplicit()
        );
        if (staticBlocks.isEmpty()) {
            // No explicit static initializer found
            log.debug("No static initializer found for {}", ctType.getQualifiedName());
            return "";
        }
        // Combine all static blocks
        StringBuilder sb = new StringBuilder();
        sb.append(ctType.getQualifiedName()).append("\n");
        for (var block : staticBlocks) {
            sb.append(block.prettyprint()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Get the Spoon model for use in other classes (like MethodSlicer).
     * This allows sharing the same parsed model across different operations.
     */
    public static CtModel getModel(String sourceRootPath) {
        return getOrCreateModel(sourceRootPath);
    }

    /**
     * Extract all constructors, setters, and getters from a class.
     *
     * @param methodSig      A method signature from the class (to identify the class)
     * @param sourceRootPath The root directory of the source code
     * @return ClassMembersData containing constructors, setters, and getters
     */
    public static ClassMemberData extractClassMembers(MethodSignature methodSig, String sourceRootPath) {
        try {
            CtModel spoonModel = getOrCreateModel(sourceRootPath);
            String className = methodSig.getDeclClassType().getFullyQualifiedName();
            CtType<?> ctType = findTypeCached(spoonModel, className);
            if (ctType == null) {
                log.debug("Type not found in Spoon model: {}", className);
                return new ClassMemberData(List.of(), List.of(), List.of());
            }
            List<String> constructors = extractAllConstructors(ctType);
            List<String> setters = extractSetters(ctType);
            List<String> getters = extractGetters(ctType);
            return new ClassMemberData(constructors, setters, getters);
        } catch (Exception e) {
            log.warn("Error extracting class members for {}: {}", methodSig, e.getMessage());
            return new ClassMemberData(List.of(), List.of(), List.of());
        }
    }

    /**
     * Extract all constructors from a type.
     */
    private static List<String> extractAllConstructors(CtType<?> ctType) {
        var constructorElements = ctType.getElements(
                element -> element instanceof spoon.reflect.declaration.CtConstructor
        );
        List<String> constructors = constructorElements.stream()
                .map(c -> c.prettyprint())
                .collect(Collectors.toList());
        log.debug("Extracted {} constructors from {}", constructors.size(), ctType.getQualifiedName());
        return constructors;
    }

    /**
     * Extract all setter methods from a type.
     * A setter is identified as a method that:
     * - Starts with "set"
     * - Has exactly one parameter
     * - Returns void
     */
    private static List<String> extractSetters(CtType<?> ctType) {
        List<String> setters = ctType.getMethods().stream()
                .filter(m -> m.getSimpleName().startsWith("set") &&
                        m.getParameters().size() == 1 &&
                        m.getType().getSimpleName().equals("void"))
                .map(CtMethod::prettyprint)
                .collect(Collectors.toList());
        log.debug("Extracted {} setters from {}", setters.size(), ctType.getQualifiedName());
        return setters;
    }

    /**
     * Extract all getter methods from a type.
     * A getter is identified as a method that:
     * - Starts with "get" or "is"
     * - Has no parameters
     * - Returns a non-void type
     * This could be too general, but we are following general conventions.
     */
    private static List<String> extractGetters(CtType<?> ctType) {
        List<String> getters = ctType.getMethods().stream()
                .filter(m -> (m.getSimpleName().startsWith("get") || m.getSimpleName().startsWith("is")) &&
                        m.getParameters().isEmpty() &&
                        !m.getType().getSimpleName().equals("void"))
                .map(CtMethod::prettyprint)
                .collect(Collectors.toList());
        log.debug("Extracted {} getters from {}", getters.size(), ctType.getQualifiedName());
        return getters;
    }

    // ToDo: It should be possible to remove the duplicated parts within imports extraction and source code extraction.
    //  Because they both need to find the same methods and constructors.
    /**
     * Extract all required imports for constructors and methods in the path.
     * This includes parameter types, return types, and types used in method bodies.
     *
     * @param entryPointSig  The entry point method signature
     * @param pathSignatures All method signatures in the path
     * @param sourceRootPath The root directory of the source code
     * @return Set of fully qualified import statements
     */
    public static Set<String> extractRequiredImports(MethodSignature entryPointSig,
                                                     List<MethodSignature> pathSignatures,
                                                     String sourceRootPath) {
        Set<String> imports = new HashSet<>();
        try {
            CtModel spoonModel = getOrCreateModel(sourceRootPath);
            String className = entryPointSig.getDeclClassType().getFullyQualifiedName();
            CtType<?> ctType = findTypeCached(spoonModel, className);
            if (ctType == null) {
                log.debug("Type not found in Spoon model: {}", className);
                return imports;
            }
            // Extract imports from constructors
            var constructorElements = ctType.getElements(
                    element -> element instanceof spoon.reflect.declaration.CtConstructor
            );
            for (var constructor : constructorElements) {
                CtConstructor<?> ctConstructor = (CtConstructor<?>) constructor;
                extractImportsFromExecutable(ctConstructor, imports);
            }
            // Extract imports from all methods in the path
            for (MethodSignature methodSig : pathSignatures) {
                extractImportsFromMethodSignature(spoonModel, methodSig, imports);
            }
            // Filter out primitive types, java.lang classes, and same-package classes
            imports = filterImports(imports, className);
            log.debug("Extracted {} imports for {}", imports.size(), className);
        } catch (Exception e) {
            log.warn("Error extracting imports: {}", e.getMessage());
        }
        return imports;
    }

    /**
     * Extract imports from a specific method signature.
     */
    private static void extractImportsFromMethodSignature(CtModel spoonModel,
                                                          MethodSignature methodSig,
                                                          Set<String> imports) {
        String className = methodSig.getDeclClassType().getFullyQualifiedName();
        CtType<?> ctType = findTypeCached(spoonModel, className);
        if (ctType == null) {
            return;
        }
        String methodName = methodSig.getName();
        if ("<init>".equals(methodName)) {
            // Constructor
            ctType.getElements(element -> element instanceof spoon.reflect.declaration.CtConstructor)
                    .forEach(c -> extractImportsFromExecutable((CtConstructor<?>) c, imports));
        } else if ("<clinit>".equals(methodName)) {
            extractImportsFromStaticInitializer(ctType, imports);
        } else {
            // Regular method
            ctType.getMethods().stream()
                    .filter(m -> m.getSimpleName().equals(methodName))
                    .forEach(m -> extractImportsFromExecutable(m, imports));
        }
    }

    /**
     * Extract imports from static initializer blocks.
     */
    private static void extractImportsFromStaticInitializer(CtType<?> ctType, Set<String> imports) {
        // Get all anonymous executable blocks (static initializers)
        var staticBlocks = ctType.getElements(
                element -> element instanceof spoon.reflect.code.CtBlock &&
                        element.getParent() instanceof CtType &&
                        !element.isImplicit()
        );
        for (var block : staticBlocks) {
            // Extract all type references from the static block
            block.getElements(element -> element instanceof spoon.reflect.reference.CtTypeReference)
                    .forEach(typeRef -> addTypeImport((spoon.reflect.reference.CtTypeReference<?>) typeRef, imports));
        }
    }

    /**
     * Extract imports from a constructor or method. Without this the model cannot figure out where to import the
     * types from.
     */
    private static void extractImportsFromExecutable(spoon.reflect.declaration.CtExecutable<?> executable,
                                                     Set<String> imports) {
        // Extract parameter types
        executable.getParameters().forEach(param -> addTypeImport(param.getType(), imports));
        // Extract return type (for methods)
        if (executable instanceof CtMethod<?> method) {
            addTypeImport(method.getType(), imports);
        }
        // Extract types from thrown exceptions
        executable.getThrownTypes().forEach(thrownType -> addTypeImport(thrownType, imports));
        // Extract types used in the method body
        if (executable.getBody() != null) {
            executable.getBody().getElements(element -> element instanceof spoon.reflect.reference.CtTypeReference)
                    .forEach(typeRef -> addTypeImport((spoon.reflect.reference.CtTypeReference<?>) typeRef, imports));
        }
    }

    /**
     * Add a type to the imports set if it needs to be imported.
     */
    private static void addTypeImport(spoon.reflect.reference.CtTypeReference<?> typeRef, Set<String> imports) {
        if (typeRef == null) {
            return;
        }
        String qualifiedName = typeRef.getQualifiedName();
        if (qualifiedName != null && !qualifiedName.isEmpty()) {
            // Handle generic types - extract the base type
            if (qualifiedName.contains("<")) {
                qualifiedName = qualifiedName.substring(0, qualifiedName.indexOf("<"));
            }
            // Handle array types
            qualifiedName = qualifiedName.replace("[]", "");
            imports.add(qualifiedName);
            // Also add generic type arguments
            typeRef.getActualTypeArguments().forEach(typeArg -> addTypeImport(typeArg, imports));
        }
    }

    /**
     * Filter out imports that don't need to be explicitly imported.
     */
    private static Set<String> filterImports(Set<String> imports, String currentClassName) {
        String currentPackage = currentClassName.substring(0,
                Math.max(currentClassName.lastIndexOf('.'), 0));
        return imports.stream()
                .filter(imp -> imp != null && !imp.isEmpty())
                .filter(imp -> imp.contains(".")) // Has a package
                .filter(imp -> !imp.startsWith("java.")) // Not from java.* packages
                .filter(imp -> !isPrimitiveOrWrapper(imp)) // Not primitive
                .filter(imp -> !imp.startsWith(currentPackage + ".") || imp.contains("$")) // Not same package unless inner class
                .collect(Collectors.toSet());
    }

    /**
     * Check if a type is a primitive type or primitive wrapper.
     */
    private static boolean isPrimitiveOrWrapper(String typeName) {
        // Check for primitive types
        Set<String> primitives = Set.of(
                "int", "long", "short", "byte", "char", "float", "double", "boolean", "void"
        );
        if (primitives.contains(typeName)) {
            return true;
        }
        // Check if it's from java.* packages (java.lang, java.util, java.io, etc.). For these we consider the model
        // would know where to import them from.
        return typeName.startsWith("java.");
    }

    /**
     * Clear the cached model and method cache.
     * Useful for testing or when processing multiple projects.
     */
    public static void clearCache() {
        model = null;
        currentSourceRoot = null;
        methodCache.clear();
        SpoonMethodFinder.clearCache();
        log.debug("Cleared all caches");
    }

    /**
     * Get cache statistics for monitoring/debugging.
     */
    public static String getCacheStats() {
        return String.format("Method cache: %d entries, %s",
                methodCache.size(), SpoonMethodFinder.getCacheStats());
    }



}
