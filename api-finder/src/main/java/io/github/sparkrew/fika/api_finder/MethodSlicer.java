package io.github.sparkrew.fika.api_finder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.signatures.MethodSignature;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.*;

import static io.github.sparkrew.fika.api_finder.SourceCodeExtractor.currentSourceRoot;

/**
 * Performs backward slicing on methods to extract only the relevant statements
 * needed to reach a specific target (usually a third-party method call).
 * <p>
 * This uses Spoon to work with actual source code and performs backward
 * data-flow and control-flow analysis to identify which statements in a
 * method actually contribute to the target call.
 * <p>
 */
public class MethodSlicer {

    private static final Logger log = LoggerFactory.getLogger(MethodSlicer.class);

    // Cache for slicing results: methodSig->targetSig -> slice
    // We keep a cache to avoid re-slicing the same methods multiple times
    // Otherwise, this takes an awful lot of time on large projects
    private static final Map<String, String> sliceCache = new HashMap<>();
    protected static int fallbackCount = 0;
    protected static int sliceCount = 0;

    /**
     * Extract method slices for all methods in a path.
     * For each method, we perform backward slicing from the call to the next method
     * in the path (or the third-party method if it's the last one).
     * The slice includes the method signature and the target method call statement.
     */
    public static List<String> extractMethodSlices(List<MethodSignature> path, String sourceRootPath) {
        List<String> slices = new ArrayList<>();
        // If no source root provided, we can't do proper slicing, nobody likes reading bytecode, maybe machines do,
        // but anyways pass the source code please
        if (sourceRootPath == null) {
            throw new IllegalArgumentException("Source root path is required for method slicing");
        }
        // Get the Spoon model once for all methods (this is cached in SourceCodeExtractor)
        CtModel model = SourceCodeExtractor.getModel(sourceRootPath);
        // Process all methods instead of the last one (for third-party call)
        for (int i = 0; i < path.size() - 1; i++) {
            MethodSignature currentMethod = path.get(i);
            MethodSignature targetCall = path.get(i + 1);
            String slice = performBackwardSlice(model, currentMethod, targetCall);
            slices.add(slice);
        }
        return slices;
    }

    /**
     * Perform backward slicing on a method to extract statements relevant to reaching
     * the target method call. This works on actual source code using Spoon.
     * Falls back to full method extraction if slicing fails.
     */
    private static String performBackwardSlice(CtModel model, MethodSignature methodSig,
                                               MethodSignature targetCall) {
        // Check cache first
        String cacheKey = methodSig.toString() + "->" + (targetCall != null ? targetCall.toString() : "LAST");
        if (sliceCache.containsKey(cacheKey)) {
            log.trace("Slice cache hit for {}", cacheKey);
            return sliceCache.get(cacheKey);
        }
        String result;
        try {
            // Find the method in the Spoon model using SourceCodeExtractor's cached lookup
            CtExecutable<?> executable = findExecutableInModel(model, methodSig);
            if (executable == null || executable.getBody() == null) {
                log.debug("Method not found or has no body: {}, falling back to full method extraction", methodSig);
                // Fall back to full method extraction
                String sourceRootPath = currentSourceRoot != null ? currentSourceRoot :
                        SourceCodeExtractor.getCurrentSourceRoot();
                result = SourceCodeExtractor.extractMethodFromSource(methodSig, sourceRootPath);
                fallbackCount++;
                sliceCache.put(cacheKey, result);
                return result != null ? result : "";
            }
            // Find the statements that invoke the target method (handles both regular methods and constructors)
            if (targetCall == null) {
                log.error("Target call is null for method {}, falling back to full method extraction", methodSig);
                // Fall back to full method extraction
                String sourceRootPath = currentSourceRoot != null ? currentSourceRoot :
                        SourceCodeExtractor.getCurrentSourceRoot();
                result = SourceCodeExtractor.extractMethodFromSource(methodSig, sourceRootPath);
                fallbackCount++;
                sliceCache.put(cacheKey, result);
                return result != null ? result : "";
            }
            List<CtElement> targetInvocations = findTargetInvocations(executable, targetCall);
            if (targetInvocations.isEmpty()) {
                // If we can't find the target invocation, fall back to full method extraction
                log.debug("Target invocation not found in method {} for target {}, falling back to full method extraction",
                        methodSig, targetCall);
                String sourceRootPath = currentSourceRoot != null ? currentSourceRoot :
                        SourceCodeExtractor.getCurrentSourceRoot();
                result = SourceCodeExtractor.extractMethodFromSource(methodSig, sourceRootPath);
                fallbackCount++;
                sliceCache.put(cacheKey, result);
                return result != null ? result : "";
            }
            // Perform backward slicing from each target invocation
            Set<CtStatement> relevantStatements = new LinkedHashSet<>();
            for (CtElement targetInvocation : targetInvocations) {
                Set<CtStatement> slice = computeBackwardSlice(executable, targetInvocation);
                relevantStatements.addAll(slice);
            }
            // Format the slice as readable code, including method signature and target call
            result = formatSlice(methodSig, executable, relevantStatements, targetInvocations);
        } catch (Exception e) {
            log.debug("Failed to slice method {}: {}, falling back to full method extraction",
                    methodSig, e.getMessage());
            // Fall back to full method extraction on any exception
            try {
                String sourceRootPath = currentSourceRoot != null ? currentSourceRoot :
                        SourceCodeExtractor.getCurrentSourceRoot();
                result = SourceCodeExtractor.extractMethodFromSource(methodSig, sourceRootPath);
                fallbackCount++;
                result = result != null ? result : "";
            } catch (Exception fallbackException) {
                log.warn("Full method extraction also failed for {}: {}",
                        methodSig, fallbackException.getMessage());
                result = "";
            }
        }
        // Cache the result
        sliceCache.put(cacheKey, result);
        sliceCount++;
        return result;
    }

    /**
     * Extract the method signature as a string (method declaration without body).
     * Returns the full declaration including modifiers, return type, method name, parameters, and throws clause.
     */
    private static String extractMethodSignature(CtExecutable<?> executable) {
        String signature = executable.prettyprint();
        // Remove the body part if present, but avoid cutting at '{' inside comments
        int braceIndex = findFirstBraceOutsideComments(signature);
        if (braceIndex > 0) {
            signature = signature.substring(0, braceIndex).trim();
        }
        return signature;
    }

    /**
     * Find the index of the first '{' that is not inside a comment.
     */
    private static int findFirstBraceOutsideComments(String text) {
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Check for line comment start
            if (!inBlockComment && i < text.length() - 1 && c == '/' && text.charAt(i + 1) == '/') {
                inLineComment = true;
                i++; // Skip next character
                continue;
            }
            // Check for block comment start
            if (!inLineComment && i < text.length() - 1 && c == '/' && text.charAt(i + 1) == '*') {
                inBlockComment = true;
                i++; // Skip next character
                continue;
            }
            // Check for block comment end
            if (inBlockComment && i < text.length() - 1 && c == '*' && text.charAt(i + 1) == '/') {
                inBlockComment = false;
                i++; // Skip next character
                continue;
            }
            // Check for line comment end
            if (inLineComment && c == '\n') {
                inLineComment = false;
                continue;
            }
            // Check for opening brace outside comments
            if (!inLineComment && !inBlockComment && c == '{') {
                return i;
            }
        }
        return -1; // Not found
    }

    /**
     * Find a method in the Spoon model by its signature.
     * Reuses SourceCodeExtractor's type caching for better performance.
     * Unfortunately, we have to re-implement some of the logic here as we need CtMethod.
     * If someone comes up with a better way to do this, please PR!
     */
    private static CtExecutable<?> findExecutableInModel(CtModel model, MethodSignature methodSig) {
        String className = methodSig.getDeclClassType().getFullyQualifiedName();
        String methodName = methodSig.getName();
        // Find the type (handles inner classes too)
        CtType<?> type = model.getAllTypes().stream()
                .filter(t -> {
                    String typeName = t.getQualifiedName();
                    // Handle both $ and . notation for inner classes
                    return typeName.equals(className) ||
                            typeName.equals(className.replace('$', '.'));
                })
                .findFirst()
                .orElse(null);
        if (type == null) {
            return null;
        }
        // Handle special method names, not too much fun having to handle all these cases, not once but twice! at least I'm listening to the Scorpions.
        if ("<init>".equals(methodName)) {
            // Constructor - find by parameter count
            int paramCount = methodSig.getParameterTypes().size();
            return type.getElements(new TypeFilter<>(spoon.reflect.declaration.CtConstructor.class))
                    .stream()
                    .filter(c -> c.getParameters().size() == paramCount)
                    .findFirst()
                    .orElse(null);
        } else if ("<clinit>".equals(methodName)) {
            // ToDo: do not return null. return the static initializer block if present
            // Static initializer - return null as we can't slice these meaningfully, finally, an easy one
            return null;
        }
        // Regular method - find by name and parameter count. Why bother to count params? bcoz Java can do overloading.
        int paramCount = methodSig.getParameterTypes().size();
        return type.getMethods().stream()
                .filter(m -> m.getSimpleName().equals(methodName))
                .filter(m -> m.getParameters().size() == paramCount)
                .findFirst()
                .orElse(null);
    }

    /**
     * Find all invocations in the method that call the target method.
     * This is our slicing criterion - the points from which we work backwards.
     */
    private static List<CtElement> findTargetInvocations(CtExecutable<?> executable, MethodSignature targetCall) {
        String targetMethodName = targetCall.getName();
        String targetClassName = targetCall.getDeclClassType().getFullyQualifiedName();
        List<CtElement> matchingElements = new ArrayList<>();
        // Handle constructor targets (<init>)
        if ("<init>".equals(targetMethodName)) {
            // Look for constructor calls (new statements)
            List<CtConstructorCall<?>> constructorCalls = executable.getElements(new TypeFilter<>(CtConstructorCall.class));
            for (CtConstructorCall<?> call : constructorCalls) {
                CtExecutableReference<?> exec = call.getExecutable();
                String callClassName = exec.getDeclaringType() != null ?
                        exec.getDeclaringType().getQualifiedName() : "";
                if (callClassName.equals(targetClassName) ||
                        callClassName.equals(targetClassName.replace('$', '.'))) {
                    matchingElements.add(call);
                }
            }
            return matchingElements;
        }
        // Regular method invocations
        List<CtInvocation<?>> allInvocations = executable.getElements(new TypeFilter<>(CtInvocation.class));
        // Filter for invocations that match the target
        List<CtInvocation<?>> matchingInvocations = allInvocations.stream()
                .filter(inv -> {
                    CtExecutableReference<?> exec = inv.getExecutable();
                    String invMethodName = exec.getSimpleName();
                    String invClassName = exec.getDeclaringType() != null ?
                            exec.getDeclaringType().getQualifiedName() : "";

                    // Match by method name and class name
                    return invMethodName.equals(targetMethodName) &&
                            (invClassName.equals(targetClassName) ||
                                    invClassName.equals(targetClassName.replace('$', '.')));
                })
                .toList();
        matchingElements.addAll(matchingInvocations);
        return matchingElements;
    }

    /**
     * Compute the backward slice from a target invocation.
     * This includes all statements that the target depends on through:
     * Data dependencies (variables used in the target)
     * Control dependencies (conditions that must be true to reach the target)
     * <p>
     * Lookups should be O(1). Hope this helps performance, otherwise you can even finish a movie while this runs.
     */
    private static Set<CtStatement> computeBackwardSlice(CtExecutable<?> executable, CtElement targetElement) {
        Set<CtStatement> slice = new LinkedHashSet<>();
        Set<CtVariableReference<?>> relevantVariables = new HashSet<>();
        Queue<CtElement> worklist = new LinkedList<>();
        // This is the statement containing the target invocation
        CtStatement targetStmt = getStatementContaining(targetElement);
        if (targetStmt == null) {
            return slice;
        }
        slice.add(targetStmt);
        worklist.add(targetElement);
        // Collect all variables used in the target invocation
        List<CtVariableAccess<?>> variableAccesses = targetElement.getElements(
                new TypeFilter<>(CtVariableAccess.class)
        );
        variableAccesses.forEach(va -> relevantVariables.add(va.getVariable()));
        // Get all statements in the method body and create an index for O(1) lookup
        List<CtStatement> allStatements = executable.getBody().getStatements();
        Map<CtStatement, Integer> stmtIndexMap = new HashMap<>();
        for (int i = 0; i < allStatements.size(); i++) {
            stmtIndexMap.put(allStatements.get(i), i);
        }
        // Work backwards through statements
        Set<CtStatement> visited = new HashSet<>();
        while (!worklist.isEmpty()) {
            CtElement current = worklist.poll();
            CtStatement currentStmt = getStatementContaining(current);
            if (currentStmt == null || !visited.add(currentStmt)) {
                continue;
            }
            Integer currentIndex = stmtIndexMap.get(currentStmt);
            if (currentIndex == null) {
                continue;
            }
            // Look at all statements before the current one
            for (int i = currentIndex - 1; i >= 0; i--) {
                CtStatement predecessor = allStatements.get(i);
                // Skip if already processed
                if (slice.contains(predecessor)) {
                    continue;
                }
                boolean isRelevant = false;
                // Check data dependencies - does this statement define a variable we use?
                if (predecessor instanceof CtAssignment<?, ?> assignment) {
                    CtExpression<?> assigned = assignment.getAssigned();
                    if (assigned instanceof CtVariableAccess<?> varAccess) {
                        CtVariableReference<?> assignedVar = varAccess.getVariable();
                        if (relevantVariables.contains(assignedVar)) {
                            isRelevant = true;
                            // Now we also care about variables used in the right-hand side
                            List<CtVariableAccess<?>> rhsVars = assignment.getAssignment().getElements(
                                    new TypeFilter<>(CtVariableAccess.class)
                            );
                            rhsVars.forEach(va -> relevantVariables.add(va.getVariable()));
                        }
                    }
                } else if (predecessor instanceof CtLocalVariable<?> varDecl) {
                    // Variable declarations
                    if (relevantVariables.contains(varDecl.getReference())) {
                        isRelevant = true;
                        // Add variables used in the initialization
                        if (varDecl.getDefaultExpression() != null) {
                            List<CtVariableAccess<?>> initVars = varDecl.getDefaultExpression().getElements(
                                    new TypeFilter<>(CtVariableAccess.class)
                            );
                            initVars.forEach(va -> relevantVariables.add(va.getVariable()));
                        }
                    }
                }
                // Check control dependencies - conditions that control whether we reach the target
                if (predecessor instanceof CtIf || predecessor instanceof CtLoop ||
                        predecessor instanceof CtSwitch) {
                    // For now, we include all control flow statements
                    // A more sophisticated approach would check if the target is in the controlled block
                    // Obviously, by more sophisticated we mean at least check something rather than lazily passing
                    // true. But, we are not gonna spend time on that, it is far more interesting to type these
                    // comments instead.
                    isRelevant = true;

                    // Add variables used in the condition
                    List<CtVariableAccess<?>> condVars = predecessor.getElements(
                            new TypeFilter<>(CtVariableAccess.class)
                    );
                    condVars.forEach(va -> relevantVariables.add(va.getVariable()));
                }
                // If this statement is relevant and not already in slice, add it
                if (isRelevant && slice.add(predecessor)) {
                    worklist.add(predecessor);
                }
            }
        }
        return slice;
    }

    /**
     * Get the statement that contains the given element.
     */
    private static CtStatement getStatementContaining(CtElement element) {
        CtElement current = element;
        while (current != null) {
            if (current instanceof CtStatement) {
                return (CtStatement) current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Format the slice into readable code.
     * Presents the method signature, relevant statements in their original order,
     * and ensures the target call is included.
     */
    private static String formatSlice(MethodSignature methodSignature, CtExecutable<?> executable,
                                      Set<CtStatement> slice, List<CtElement> targetInvocations) {
        StringBuilder sb = new StringBuilder();
        // Add method signature
        sb.append(extractMethodSignature(executable)).append(" {\n");
        // Get all statements in order
        List<CtStatement> allStatements = executable.getBody().getStatements();
        // Ensure target invocation statements are in the slice
        for (CtElement targetInvocation : targetInvocations) {
            CtStatement targetStmt = getStatementContaining(targetInvocation);
            if (targetStmt != null) {
                slice.add(targetStmt);
            }
        }
        // Keep only the statements in the slice, in their original order
        List<CtStatement> orderedSlice = allStatements.stream()
                .filter(slice::contains)
                .toList();
        // Add each statement, we are super happy that the code could reach this point.
        for (CtStatement stmt : orderedSlice) {
            sb.append("    ").append(stmt.prettyprint()).append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Clear the slice cache. Useful when processing multiple projects.
     */
    public static void clearCache() {
        sliceCache.clear();
        log.debug("Cleared slice cache");
    }

    /**
     * Get cache statistics for monitoring/debugging.
     */
    public static String getCacheStats() {
        return String.format("Slice cache: %d entries", sliceCache.size());
    }
}
