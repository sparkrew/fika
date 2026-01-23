package io.github.sparkrew.fika.api_finder.utils;

import sootup.core.signatures.MethodSignature;

public class NameFilter {

    /**
     * Get filtered method signature WITH parameter types to properly handle method overloading.
     * This should be used when we need to uniquely identify methods that may be overloaded.
     */
    public static String getFilteredMethodSignatureWithParams(MethodSignature method) {
        String className = filterName(method.getDeclClassType().getFullyQualifiedName());
        String methodName = filterName(method.getName());
        String params = method.getParameterTypes().stream()
                .map(type -> filterName(type.toString()))
                .collect(java.util.stream.Collectors.joining(", "));
        return className + "." + methodName + "(" + params + ")";
    }

    public static String filterName(String name) {
        // Replace $ followed by digit (e.g., Array$1234 -> Array) with nothing
        name = name.replaceAll("\\$\\d+", "");
        // Replace $ followed by letter (e.g. Something$ArrayInitializer -> Something.ArrayInitializer) with a dot
        name = name.replaceAll("\\$(?=[A-Za-z])", ".");
        return name;
    }

    public static String filterNameSimple(String name) {
        // Replace $ followed by digit (e.g., $Array1234) with nothing
        name = name.replaceAll("\\$\\d+", "");
        return name;
    }

    public static String filterNameSimple2(String name) {
        name = name.replaceAll("\\$(?=[A-Za-z])", ".");
        return name;
    }
}
