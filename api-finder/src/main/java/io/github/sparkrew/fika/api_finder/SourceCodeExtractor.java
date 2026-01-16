package io.github.sparkrew.fika.api_finder;

import io.github.sparkrew.fika.api_finder.model.ClassMemberData;
import io.github.sparkrew.fika.api_finder.utils.SpoonMethodFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.signatures.MethodSignature;
import spoon.MavenLauncher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.*;
import spoon.reflect.visitor.CtScanner;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Extracts actual source code from Java files using Spoon.
 * Enhanced to add tracking comments for method calls along paths.
 */
public class SourceCodeExtractor {

    private static final Logger log = LoggerFactory.getLogger(SourceCodeExtractor.class);
    private static final Map<String, String> methodCache = new HashMap<>();
    private static final Map<String, CtType<?>> typeCache = new HashMap<>();
    private static final Map<String, Integer> invocationCountCache = new HashMap<>();
    protected static String currentSourceRoot;
    private static CtModel model;

    /**
     * Initialize or retrieve the Spoon model for the given source root.
     * This is cached to avoid re-parsing the entire source tree multiple times.
     */
    private static CtModel getOrCreateModel(String sourceRootPath) {
        if (model != null && sourceRootPath.equals(currentSourceRoot)) {
            return model;
        }
        log.info("Building Spoon model from source root: {}", sourceRootPath);
        try {
            MavenLauncher launcher = new MavenLauncher(sourceRootPath,
                    MavenLauncher.SOURCE_TYPE.APP_SOURCE);
            launcher.getEnvironment().setNoClasspath(true);
            launcher.getEnvironment().setCommentEnabled(true);
            launcher.getEnvironment().disableConsistencyChecks();
            model = launcher.buildModel();
            currentSourceRoot = sourceRootPath;
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
     * Extract a method's source code with optional path tracking comment.
     *
     * @param methodSig      The method signature to extract
     * @param sourceRootPath The root directory of the source code
     * @param nextMethodSig  The next method in the path (null if this is the last method)
     * @return The source code of the method with tracking comment added if applicable
     */
    public static String extractMethodFromSource(MethodSignature methodSig, String sourceRootPath,
                                                 MethodSignature nextMethodSig) {
        String cacheKey = methodSig.toString() + (nextMethodSig != null ? "|" + nextMethodSig : "");
        if (methodCache.containsKey(cacheKey)) {
            log.trace("Method cache hit for {}", cacheKey);
            return methodCache.get(cacheKey);
        }
        try {
            CtModel spoonModel = getOrCreateModel(sourceRootPath);
            String className = MethodExtractor.filterNameSimple(methodSig.getDeclClassType().getFullyQualifiedName());
            String methodName = methodSig.getName();
            // Handle inner classes - Spoon uses $ for inner classes
            CtType<?> ctType = findTypeCached(spoonModel, className);
            // If not found and it's an inner class, try to navigate through the outer class
            if (ctType == null && className.contains("$")) {
                ctType = findInnerClass(spoonModel, className);
            }
            if (ctType == null) {
                log.warn("Type not found in Spoon model: {}", className);
                methodCache.put(cacheKey, null);
                return null;
            }
            String sourceCode = null;
            if ("<init>".equals(methodName)) {
                sourceCode = extractConstructor(ctType, methodSig, nextMethodSig);
            } else if ("<clinit>".equals(methodName)) {
                sourceCode = extractStaticInitializer(ctType);
            } else {
                sourceCode = extractRegularMethod(ctType, methodName, methodSig, nextMethodSig);
            }
            if (sourceCode == null) {
                log.warn("Method {} not found in type {}", methodName, className);
            }
            methodCache.put(cacheKey, sourceCode);
            if (sourceCode != null) {
                log.trace("Cached method source for {}", cacheKey);
            }
            return sourceCode;
        } catch (Exception e) {
            log.warn("Error extracting source code for {}: {}", methodSig, e.getMessage());
            methodCache.put(cacheKey, null);
            return null;
        }
    }

    /**
     * Extract a regular method by name and parameter types and adds comments.
     * Handles method overloading by matching the full signature.
     */
    private static String extractRegularMethod(CtType<?> ctType, String methodName,
                                               MethodSignature methodSig, MethodSignature nextMethodSig) {
        CtMethod<?> method = SpoonMethodFinder.findRegularMethod(ctType, methodName, methodSig);
        if (method == null) {
            return null;
        }
        if (nextMethodSig != null) {
            return addPathTrackingComment(method, nextMethodSig);
        }
        return method.prettyprint();
    }

    /**
     * Extract a constructor with path tracking comment.
     */
    private static String extractConstructor(CtType<?> ctType, MethodSignature methodSig,
                                             MethodSignature nextMethodSig) {
        CtConstructor<?> constructor = SpoonMethodFinder.findConstructor(ctType, methodSig);
        if (constructor == null) {
            return null;
        }
        if (nextMethodSig != null) {
            return addPathTrackingComment(constructor, nextMethodSig);
        }
        return constructor.prettyprint();
    }

    /**
     * Add a tracking comment after the method call that leads to the next method in the path.
     * Uses string manipulation to insert an inline comment marking the path step.
     */
    private static String addPathTrackingComment(spoon.reflect.declaration.CtExecutable<?> executable,
                                                 MethodSignature nextMethodSig) {
        if (executable.getBody() == null) {
            return executable.prettyprint();
        }
        String nextMethodName = nextMethodSig.getName();
        String nextClassName = MethodExtractor.filterNameSimple(nextMethodSig.getDeclClassType().getFullyQualifiedName());
        String simpleClassName = nextClassName.substring(nextClassName.lastIndexOf('.') + 1);
        PathCallFinder finder = new PathCallFinder(nextMethodName, nextClassName);
        executable.getBody().accept(finder);
        if (finder.targetInvocation == null) {
            log.debug("Target invocation not found: {}.{} in {}",
                    simpleClassName, nextMethodName, executable.getSignature());
            return executable.prettyprint();
        }
        CtStatement statement = finder.targetInvocation.getParent(CtStatement.class);
        if (statement != null) {
            try {
                // Clone the executable to avoid modifying the cached model
                spoon.reflect.declaration.CtExecutable<?> clonedExecutable = executable.clone();
                String methodDisplay = "<init>".equals(nextMethodName) ?
                        "new " + simpleClassName + "(...)" :
                        simpleClassName + "." + nextMethodName + "(...)";
                String commentText = String.format(
                        "PATH: Test should invoke the next %s [step in execution path]",
                        methodDisplay
                );
                try {
                    PathCallFinder clonedFinder = new PathCallFinder(nextMethodName, nextClassName);
                    clonedExecutable.getBody().accept(clonedFinder);
                    if (clonedFinder.targetInvocation != null) {
                        CtStatement clonedStatement = clonedFinder.targetInvocation.getParent(CtStatement.class);
                        if (clonedStatement != null) {
                            clonedStatement.addComment(
                                    executable.getFactory().Code().createInlineComment(commentText)
                            );
                            return clonedExecutable.prettyprint();
                        }
                    }
                } catch (Exception spoonApiException) {
                    log.trace("Spoon comment API failed, falling back to string manipulation: {}",
                            spoonApiException.getMessage());
                }
                String originalCode = executable.prettyprint();
                String statementStr = statement.toString();
                int statementPos = originalCode.indexOf(statementStr);
                if (statementPos != -1) {
                    int endPos = statementPos + statementStr.length();
                    while (endPos < originalCode.length() &&
                            originalCode.charAt(endPos) != ';' &&
                            originalCode.charAt(endPos) != '}') {
                        endPos++;
                    }
                    if (endPos < originalCode.length() && originalCode.charAt(endPos) == ';') {
                        endPos++;
                    }
                    String before = originalCode.substring(0, endPos);
                    String after = originalCode.substring(endPos);
                    String comment = " // " + commentText;
                    return before + comment + after;
                }
            } catch (Exception e) {
                log.warn("Failed to add tracking comment for {}: {}",
                        nextMethodSig, e.getMessage());
            }
        }
        return executable.prettyprint();
    }

    private static CtType<?> findTypeCached(CtModel spoonModel, String fullyQualifiedName) {
        return SpoonMethodFinder.findTypeCached(spoonModel, fullyQualifiedName);
    }

    /**
     * Find an inner class by navigating through the outer class.
     * Handles cases where the direct lookup fails for inner classes.
     */
    private static CtType<?> findInnerClass(CtModel spoonModel, String fullyQualifiedName) {
        if (!fullyQualifiedName.contains("$")) {
            return null;
        }
        // Split on $ to get outer class and inner class path
        String[] parts = fullyQualifiedName.split("\\$");
        String outerClassName = parts[0];
        // First try to find the outer class
        CtType<?> currentType = findTypeCached(spoonModel, outerClassName);
        if (currentType == null) {
            log.debug("Outer class not found: {}", outerClassName);
            return null;
        }
        for (int i = 1; i < parts.length; i++) {
            String innerClassName = parts[i];
            CtType<?> foundInner = null;
            for (CtType<?> nestedType : currentType.getNestedTypes()) {
                if (nestedType.getSimpleName().equals(innerClassName)) {
                    foundInner = nestedType;
                    break;
                }
            }
            if (foundInner == null) {
                log.debug("Inner class {} not found in {}", innerClassName, currentType.getQualifiedName());
                return null;
            }
            currentType = foundInner;
        }
        log.debug("Successfully found inner class: {}", fullyQualifiedName);
        return currentType;
    }

    private static String extractStaticInitializer(CtType<?> ctType) {
        if (ctType == null) {
            log.warn("Cannot extract static initializer from null type");
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ctType.getQualifiedName()).append("\n\n");
        // Get all static initializer blocks (CtAnonymousExecutable with modifier STATIC)
        var staticBlocks = ctType.getElements(
                element -> element instanceof spoon.reflect.declaration.CtAnonymousExecutable &&
                        ((spoon.reflect.declaration.CtAnonymousExecutable) element)
                                .getModifiers().contains(spoon.reflect.declaration.ModifierKind.STATIC)
        );
        // Get all static fields with initializers
        var staticFields = ctType.getFields().stream()
                .filter(field -> field.getModifiers().contains(spoon.reflect.declaration.ModifierKind.STATIC))
                .filter(field -> field.getDefaultExpression() != null)
                .toList();
        if (staticBlocks.isEmpty() && staticFields.isEmpty()) {
            log.warn("No static initializer or static field initializations found for {}", 
                    ctType.getQualifiedName());
            return null;
        }
        // Add static field declarations with initializers
        if (!staticFields.isEmpty()) {
            sb.append("// Static field initializations\n");
            for (var field : staticFields) {
                sb.append(field.prettyprint()).append("\n");
            }
            if (!staticBlocks.isEmpty()) {
                sb.append("\n");
            }
        }
        // Add static blocks
        if (!staticBlocks.isEmpty()) {
            sb.append("// Static initializer blocks\n");
            for (var block : staticBlocks) {
                sb.append("static ").append(block.prettyprint()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Count how many times a target method is invoked within a caller method.
     * This is a static count based on source code analysis.
     *
     * @param callerSig      The method signature of the caller
     * @param targetSig      The method signature of the target method to count
     * @param sourceRootPath The root directory of the source code
     * @return The number of times the target method is invoked in the caller
     */
    public static int countMethodInvocations(MethodSignature callerSig, MethodSignature targetSig,
                                             String sourceRootPath) {
        String cacheKey = callerSig.toString() + " -> " + targetSig.toString();
        if (invocationCountCache.containsKey(cacheKey)) {
            log.trace("Invocation count cache hit for {}", cacheKey);
            return invocationCountCache.get(cacheKey);
        }
        try {
            CtModel spoonModel = getOrCreateModel(sourceRootPath);
            String className = callerSig.getDeclClassType().getFullyQualifiedName();
            String methodName = callerSig.getName();
            log.debug("Counting invocations in {}.{}", className, methodName);
            CtType<?> ctType = findTypeCached(spoonModel, className);
            if (ctType == null) {
                log.debug("Type not found in Spoon model: {}", className);
                return 1;
            }
            spoon.reflect.declaration.CtExecutable<?> executable;
            if ("<init>".equals(methodName)) {
                executable = SpoonMethodFinder.findConstructor(ctType, callerSig);
            } else if ("<clinit>".equals(methodName)) {
                return 1;
            } else {
                executable = SpoonMethodFinder.findRegularMethod(ctType, methodName, callerSig);
            }
            if (executable == null || executable.getBody() == null) {
                log.warn("Method or body not found for {}", callerSig);
                return 1; 
            }
            String targetMethodName = targetSig.getName();
            String targetClassName = targetSig.getDeclClassType().getFullyQualifiedName();
            InvocationCounter counter = new InvocationCounter(targetMethodName, targetClassName);
            executable.getBody().accept(counter);
            int count = counter.getCount();
            int result = count > 0 ? count : 1;
            log.debug("Found {} invocations of {} in {}", result, targetSig, callerSig);
            invocationCountCache.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            log.warn("Error counting invocations for {} -> {}: {}", 
                    callerSig, targetSig, e.getMessage());
            int defaultCount = 1;
            invocationCountCache.put(cacheKey, defaultCount);
            return defaultCount;
        }
    }

    /**
     * Get the Spoon model for use in other classes.
     * This allows sharing the same parsed model across different operations.
     */
    public static CtModel getModel(String sourceRootPath) {
        return getOrCreateModel(sourceRootPath);
    }

    /**
     * Extract all constructors, field declarations, and methods that modify fields from a class.
     *
     * @param methodSig      A method signature from the class (to identify the class)
     * @param sourceRootPath The root directory of the source code
     * @return ClassMembersData containing constructors, field declarations, field-modifying methods, and required imports
     */
    public static ClassMemberData extractClassMembers(MethodSignature methodSig, String sourceRootPath) {
        try {
            CtModel spoonModel = getOrCreateModel(sourceRootPath);
            String className = MethodExtractor.filterNameSimple(methodSig.getDeclClassType().getFullyQualifiedName());
            CtType<?> ctType = findTypeCached(spoonModel, className);
            if (ctType == null) {
                log.debug("Type not found in Spoon model: {}", className);
                return new ClassMemberData(List.of(), List.of(), List.of(), Set.of());
            }
            List<String> constructors = extractAllConstructors(ctType);
            List<String> fieldDeclarations = extractFieldDeclarations(ctType);
            // Extract field names to identify field-modifying methods
            Set<String> fieldNames = extractFieldNames(ctType);
            List<String> fieldModifiers = extractFieldModifyingMethods(ctType, fieldNames);
            Set<String> imports = new HashSet<>();
            extractImportsFromClassMembers(ctType, imports, className, fieldNames);
            return new ClassMemberData(constructors, fieldDeclarations, fieldModifiers, imports);
        } catch (Exception e) {
            log.warn("Error extracting class members for {}: {}", methodSig, e.getMessage());
            return new ClassMemberData(List.of(), List.of(), List.of(), Set.of());
        }
    }

    /**
     * Extract all constructors from a type.
     * If all constructors are private, also extracts public static factory methods that return an instance of the class.
     */
    private static List<String> extractAllConstructors(CtType<?> ctType) {
        var constructorElements = ctType.getElements(
                element -> element instanceof spoon.reflect.declaration.CtConstructor
        );
        List<String> constructors = constructorElements.stream()
                .map(CtElement::prettyprint)
                .collect(Collectors.toList());
        // Check if any constructor is private
        boolean anyConstructorsPrivate = constructorElements.stream()
                .anyMatch(element -> ((CtConstructor<?>) element).isPrivate());
        // If any constructors are private, extract public static factory methods
        if (anyConstructorsPrivate && !constructorElements.isEmpty()) {
            log.debug("One or more constructors are private in {}, extracting factory methods", ctType.getQualifiedName());
            List<String> factoryMethods = extractPublicStaticFactoryMethods(ctType);
            constructors.addAll(factoryMethods);
            log.debug("Added {} factory methods to constructors", factoryMethods.size());
        }
        log.debug("Extracted {} constructors from {}", constructors.size(), ctType.getQualifiedName());
        return constructors;
    }
    
    /**
     * Extract public static methods that return an instance of the class (factory methods).
     * These methods typically serve as alternatives to constructors when constructors are private.
     */
    private static List<String> extractPublicStaticFactoryMethods(CtType<?> ctType) {
        String className = ctType.getQualifiedName();
        List<String> factoryMethods = ctType.getMethods().stream()
                .filter(method -> method.isPublic() && method.isStatic())
                .filter(method -> {
                    // Check if return type matches the class type
                    String returnType = method.getType().getQualifiedName();
                    return returnType.equals(className);
                })
                .map(CtMethod::prettyprint)
                .collect(Collectors.toList());
        log.debug("Found {} factory methods in {}", factoryMethods.size(), ctType.getQualifiedName());
        return factoryMethods;
    }

    /**
     * Extract all field declarations (instance and static variables) from a type.
     */
    private static List<String> extractFieldDeclarations(CtType<?> ctType) {
        List<String> fieldDeclarations = ctType.getFields().stream()
                .map(CtElement::prettyprint)
                .collect(Collectors.toList());
        log.debug("Extracted {} field declarations from {}", fieldDeclarations.size(), ctType.getQualifiedName());
        return fieldDeclarations;
    }

    /**
     * Extract names of all instance and class (static) fields from a type.
     */
    private static Set<String> extractFieldNames(CtType<?> ctType) {
        Set<String> fieldNames = ctType.getFields().stream()
                .map(CtNamedElement::getSimpleName)
                .collect(Collectors.toSet());
        log.debug("Extracted {} field names from {}", fieldNames.size(), ctType.getQualifiedName());
        return fieldNames;
    }

    /**
     * Extract all methods that assign values to the given fields.
     * This includes:
     * - Methods with assignments to fields (this.field = value or field = value)
     * - Methods that call field.add(), field.put(), etc. (collection modifications)
     * Only includes methods with void return type.
     */
    private static List<String> extractFieldModifyingMethods(CtType<?> ctType, Set<String> fieldNames) {
        List<String> modifiers = new ArrayList<>();
        for (CtMethod<?> method : ctType.getMethods()) {
            if (method.getType().getSimpleName().equals("void") && methodModifiesFields(method, fieldNames)) {
                modifiers.add(method.prettyprint());
            }
        }
        log.debug("Extracted {} field-modifying methods from {}", modifiers.size(), ctType.getQualifiedName());
        return modifiers;
    }

    /**
     * Check if a method modifies any of the given fields.
     */
    private static boolean methodModifiesFields(CtMethod<?> method, Set<String> fieldNames) {
        if (method.getBody() == null) {
            return false;
        }
        FieldModificationChecker checker = new FieldModificationChecker(fieldNames);
        method.getBody().accept(checker);
        return checker.modifiesField();
    }

    /**
     * Extract imports from all constructors, fields, and field-modifying methods in a type.
     */
    private static void extractImportsFromClassMembers(CtType<?> ctType, Set<String> imports, 
                                                       String className, Set<String> fieldNames) {
        var constructorElements = ctType.getElements(
                element -> element instanceof spoon.reflect.declaration.CtConstructor
        );
        for (var constructor : constructorElements) {
            CtConstructor<?> ctConstructor = (CtConstructor<?>) constructor;
            extractImportsFromExecutable(ctConstructor, imports);
        }
        ctType.getFields().forEach(field -> {
            addTypeImport(field.getType(), imports);
        });
        for (CtMethod<?> method : ctType.getMethods()) {
            if (methodModifiesFields(method, fieldNames)) {
                extractImportsFromExecutable(method, imports);
            }
        }
        // Also extract imports from getters (they may be needed to verify field values in tests). We can remove this if more efficiency is needed.
        ctType.getMethods().stream()
                .filter(m -> (m.getSimpleName().startsWith("get") || m.getSimpleName().startsWith("is")) &&
                        m.getParameters().isEmpty() &&
                        !m.getType().getSimpleName().equals("void"))
                .forEach(m -> extractImportsFromExecutable(m, imports));
        Set<String> filteredImports = filterImports(imports, className);
        imports.clear();
        imports.addAll(filteredImports);
        log.debug("Extracted {} imports from class members of {}", imports.size(), className);
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
            String className = MethodExtractor.filterNameSimple(entryPointSig.getDeclClassType().getFullyQualifiedName());
            CtType<?> ctType = findTypeCached(spoonModel, className);
            if (ctType == null) {
                log.debug("Type not found in Spoon model: {}", className);
                return imports;
            }
            var constructorElements = ctType.getElements(
                    element -> element instanceof spoon.reflect.declaration.CtConstructor
            );
            for (var constructor : constructorElements) {
                CtConstructor<?> ctConstructor = (CtConstructor<?>) constructor;
                extractImportsFromExecutable(ctConstructor, imports);
            }
            for (MethodSignature methodSig : pathSignatures) {
                extractImportsFromMethodSignature(spoonModel, methodSig, imports);
            }
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
        String className = MethodExtractor.filterNameSimple(methodSig.getDeclClassType().getFullyQualifiedName());
        CtType<?> ctType = findTypeCached(spoonModel, className);
        if (ctType == null) {
            return;
        }
        String methodName = methodSig.getName();
        if ("<init>".equals(methodName)) {
            ctType.getElements(element -> element instanceof spoon.reflect.declaration.CtConstructor)
                    .forEach(c -> extractImportsFromExecutable((CtConstructor<?>) c, imports));
        } else if ("<clinit>".equals(methodName)) {
            extractImportsFromStaticInitializer(ctType, imports);
        } else {
            ctType.getMethods().stream()
                    .filter(m -> m.getSimpleName().equals(methodName))
                    .forEach(m -> extractImportsFromExecutable(m, imports));
        }
    }

    /**
     * Extract imports from static initializer blocks and static field initializations.
     */
    private static void extractImportsFromStaticInitializer(CtType<?> ctType, Set<String> imports) {
        // Extract from static blocks
        var staticBlocks = ctType.getElements(
                element -> element instanceof spoon.reflect.declaration.CtAnonymousExecutable &&
                        ((spoon.reflect.declaration.CtAnonymousExecutable) element)
                                .getModifiers().contains(spoon.reflect.declaration.ModifierKind.STATIC)
        );
        for (var block : staticBlocks) {
            block.getElements(element -> element instanceof spoon.reflect.reference.CtTypeReference)
                    .forEach(typeRef -> addTypeImport((spoon.reflect.reference.CtTypeReference<?>) typeRef, imports));
        }
        // Extract from static fields with initializers
        ctType.getFields().stream()
                .filter(field -> field.getModifiers().contains(spoon.reflect.declaration.ModifierKind.STATIC))
                .filter(field -> field.getDefaultExpression() != null)
                .forEach(field -> {
                    addTypeImport(field.getType(), imports);
                    field.getDefaultExpression().getElements(element -> element instanceof spoon.reflect.reference.CtTypeReference)
                            .forEach(typeRef -> addTypeImport((spoon.reflect.reference.CtTypeReference<?>) typeRef, imports));
                });
    }

    /**
     * Extract imports from a constructor or method. Without this the model cannot figure out where to import the
     * types from.
     */
    private static void extractImportsFromExecutable(spoon.reflect.declaration.CtExecutable<?> executable,
                                                     Set<String> imports) {
        executable.getParameters().forEach(param -> addTypeImport(param.getType(), imports));
        if (executable instanceof CtMethod<?> method) {
            addTypeImport(method.getType(), imports);
        }
        executable.getThrownTypes().forEach(thrownType -> addTypeImport(thrownType, imports));
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
            if (qualifiedName.contains("<")) {
                qualifiedName = qualifiedName.substring(0, qualifiedName.indexOf("<"));
            }
            qualifiedName = qualifiedName.replace("[]", "");
            imports.add(qualifiedName);
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
                .filter(imp -> imp.contains("."))
                .filter(imp -> !imp.startsWith("java."))
                .filter(imp -> !isPrimitiveOrWrapper(imp))
                .filter(imp -> !imp.startsWith(currentPackage + ".") || imp.contains("$"))
                .collect(Collectors.toSet());
    }

    /**
     * Check if a type is a primitive type or primitive wrapper.
     */
    private static boolean isPrimitiveOrWrapper(String typeName) {
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
     * Check if two class names match, handling inner class naming differences.
     * Spoon uses '.' but Soot might use '$' for inner classes.
     */
    private static boolean classNamesMatch(String spoonClassName, String sootClassName) {
        if (spoonClassName.equals(sootClassName)) {
            return true;
        }
        // Handle inner classes: Spoon uses '.' but Soot might use '$'
        String normalizedSpoon = spoonClassName.replace('.', '$');
        String normalizedSoot = sootClassName.replace('.', '$');
        return normalizedSpoon.equals(normalizedSoot);
    }

    /**
     * Check if class names match, considering inheritance.
     * Returns true if: The classes match exactly, or the target class is a subclass of the invoked class
     * (inherited method)
     */
    private static boolean classNamesMatchWithInheritance(String spoonClassName, String sootClassName) {
        // Direct match
        if (classNamesMatch(spoonClassName, sootClassName)) {
            return true;
        }
        // Check if the target class is a subclass of the declaring class
        // This handles cases where a method is defined in a parent class
        // but we are tracking the call through a subclass
        try {
            CtType<?> targetType = findTypeCached(model, sootClassName);
            if (targetType != null) {
                return isSubclassOf(targetType, spoonClassName);
            }
        } catch (Exception e) {
            log.trace("Could not check inheritance for {} and {}: {}",
                    sootClassName, spoonClassName, e.getMessage());
        }
        return false;
    }

    /**
     * Check if the given type is a subclass of the specified parent class name.
     * Also checks implemented interfaces.
     */
    private static boolean isSubclassOf(CtType<?> type, String parentClassName) {
        if (type == null) {
            return false;
        }
        // Check direct superclass
        if (type.getSuperclass() != null) {
            String superClassName = type.getSuperclass().getQualifiedName();
            if (classNamesMatch(superClassName, parentClassName)) {
                return true;
            }
            // Recursively check parent classes
            try {
                CtType<?> superType = findTypeCached(model, superClassName);
                if (isSubclassOf(superType, parentClassName)) {
                    return true;
                }
            } catch (Exception e) {
                log.trace("Could not resolve superclass {}: {}", superClassName, e.getMessage());
            }
        }
        // Sometimes, it is an interface. Check implemented interfaces (including inherited interfaces)
        Set<spoon.reflect.reference.CtTypeReference<?>> interfaces = type.getSuperInterfaces();
        for (spoon.reflect.reference.CtTypeReference<?> interfaceRef : interfaces) {
            if (interfaceRef == null) continue;
            String interfaceName = interfaceRef.getQualifiedName();
            if (classNamesMatch(interfaceName, parentClassName)) {
                return true;
            }
            // Recursively check parent interfaces
            try {
                CtType<?> interfaceType = findTypeCached(model, interfaceName);
                if (isSubclassOf(interfaceType, parentClassName)) {
                    return true;
                }
            } catch (Exception e) {
                log.trace("Could not resolve interface {}: {}", interfaceName, e.getMessage());
            }
        }
        return false;
    }

    /**
     * Clear the cached model and method cache.
     * Useful for testing or when processing multiple projects.
     */
    public static void clearCaches() {
        methodCache.clear();
        typeCache.clear();
        invocationCountCache.clear();
        model = null;
        currentSourceRoot = null;
        SpoonMethodFinder.clearCache();
    }

    /**
     * Get cache statistics for monitoring/debugging.
     */
    public static String getCacheStats() {
        return String.format("Method cache: %d entries, Invocation count cache: %d entries, %s",
                methodCache.size(), invocationCountCache.size(), SpoonMethodFinder.getCacheStats());
    }

    /**
     * Scanner to find the target method invocation in the path.
     * Handles inheritance - matches if the invoked class is the target class
     * Or if the target class is a subclass of the invoked class.
     */
    private static class PathCallFinder extends CtScanner {
        private final String targetMethodName;
        private final String targetClassName;
        CtInvocation<?> targetInvocation;

        PathCallFinder(String targetMethodName, String targetClassName) {
            this.targetMethodName = targetMethodName;
            this.targetClassName = targetClassName;
        }

        @Override
        public <T> void visitCtInvocation(CtInvocation<T> invocation) {
            if (targetInvocation != null) {
                return;
            }
            // Handle constructor calls (<init>)
            if ("<init>".equals(targetMethodName)) {
                if (invocation.getExecutable().getDeclaringType() != null) {
                    String invokedClass = invocation.getExecutable().getDeclaringType().getQualifiedName();
                    if (classNamesMatch(invokedClass, targetClassName)) {
                        targetInvocation = invocation;
                        return;
                    }
                }
            } else {
                // Regular method call
                String invokedMethod = invocation.getExecutable().getSimpleName();
                if (invokedMethod.equals(targetMethodName)) {
                    if (invocation.getExecutable().getDeclaringType() != null) {
                        String invokedClass = invocation.getExecutable().getDeclaringType().getQualifiedName();
                        // Match if the declaring class matches or if target is a subclass
                        if (classNamesMatchWithInheritance(invokedClass, targetClassName)) {
                            targetInvocation = invocation;
                            return;
                        }
                    }
                }
            }
            super.visitCtInvocation(invocation);
        }
    }

    /**
     * Scanner to count all invocations of a target method.
     */
    private static class InvocationCounter extends CtScanner {
        private final String targetMethodName;
        private final String targetClassName;
        private int count = 0;

        InvocationCounter(String targetMethodName, String targetClassName) {
            this.targetMethodName = targetMethodName;
            this.targetClassName = targetClassName;
        }

        @Override
        public <T> void visitCtInvocation(CtInvocation<T> invocation) {
            // Handle constructor calls (<init>)
            if ("<init>".equals(targetMethodName)) {
                if (invocation.getExecutable().getDeclaringType() != null) {
                    String invokedClass = invocation.getExecutable().getDeclaringType().getQualifiedName();
                    if (classNamesMatch(invokedClass, targetClassName)) {
                        count++;
                    }
                }
            } else {
                // Regular method call
                String invokedMethod = invocation.getExecutable().getSimpleName();
                if (invokedMethod.equals(targetMethodName)) {
                    if (invocation.getExecutable().getDeclaringType() != null) {
                        String invokedClass = invocation.getExecutable().getDeclaringType().getQualifiedName();
                        if (classNamesMatchWithInheritance(invokedClass, targetClassName)) {
                            count++;
                        }
                    }
                }
            }
            super.visitCtInvocation(invocation);
        }

        public int getCount() {
            return count;
        }
    }

    /**
     * Scanner to check if a method modifies any of the given fields.
     * Detects:
     * - Direct assignments: this.field = value or field = value
     * - Field method calls: field.add(...), field.put(...), etc.
     */
    private static class FieldModificationChecker extends CtScanner {
        private final Set<String> fieldNames;
        private boolean modifies = false;
        FieldModificationChecker(Set<String> fieldNames) {
            this.fieldNames = fieldNames;
        }
        @Override
        public <T, A extends T> void visitCtAssignment(spoon.reflect.code.CtAssignment<T, A> assignment) {
            if (modifies) return;
            if (assignment.getAssigned() instanceof spoon.reflect.code.CtFieldWrite<?> fieldWrite) {
                String fieldName = fieldWrite.getVariable().getSimpleName();
                if (fieldNames.contains(fieldName)) {
                    modifies = true;
                    return;
                }
            }
            super.visitCtAssignment(assignment);
        }

        @Override
        public <T> void visitCtInvocation(CtInvocation<T> invocation) {
            if (modifies) return;
            if (invocation.getTarget() instanceof spoon.reflect.code.CtFieldRead<?> fieldRead) {
                String fieldName = fieldRead.getVariable().getSimpleName();
                if (fieldNames.contains(fieldName)) {
                    // This is potentially a method call on a field (e.g., list.add(...), map.put(...))
                    modifies = true;
                    return;
                }
            }
            super.visitCtInvocation(invocation);
        }

        public boolean modifiesField() {
            return modifies;
        }
    }
}
