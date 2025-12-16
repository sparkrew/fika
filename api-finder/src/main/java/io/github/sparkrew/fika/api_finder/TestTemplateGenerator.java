package io.github.sparkrew.fika.api_finder;

import io.github.sparkrew.fika.api_finder.model.ThirdPartyPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.views.JavaView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Generates test templates by replacing placeholders in the template file
 * with actual values extracted from the analysis results.
 */
public class TestTemplateGenerator {

    private static final Logger log = LoggerFactory.getLogger(TestTemplateGenerator.class);
    private static final String TEMPLATE_RESOURCE_PATH = "/Template.java";

    /**
     * Generate a test template for a given third-party path.
     *
     * @param thirdPartyPath The path containing entry point and third-party method
     * @param view           The JavaView to check method modifiers
     * @return The filled template with replaced placeholders
     */
    public static String generateTestTemplate(ThirdPartyPath thirdPartyPath, JavaView view) {
        try {
            // We have a template in the resources folder. It has basic boilerplate code with placeholders for a JUnit test.
            String template = readTemplateFile();
            MethodSignature entryPoint = thirdPartyPath.entryPoint();
            String entryPointClassName = extractSimpleClassName(entryPoint.getDeclClassType().getFullyQualifiedName());
            String entryPointPackage = extractPackageName(entryPoint.getDeclClassType().getFullyQualifiedName());
            String entryPointMethodName = entryPoint.getName();
            // ToDo: Change the test class name when a proper name is decided.
            String testClassName = entryPointClassName + "Fika" + "Test";
            String testMethodName = "test" + capitalizeFirstLetter(entryPointMethodName);
            String entryPointVarName = decapitalizeFirstLetter(entryPointClassName);
            // Check if the entry point method is static
            boolean isStatic = isMethodStatic(entryPoint, view);
            // Here, we replace placeholders in the template.
            template = template.replace("packagename", entryPointPackage);
            template = template.replace("TestNameTest", testClassName);
            template = template.replace("testname()", testMethodName + "()");
            if (isStatic) {
                // For static methods, we don't need to instantiate the class, remove the instantiation line
                template = template.replaceAll("\\s*EntryPointClass entryPointClass = " +
                        "new EntryPointClass\\(requiredClassParameters\\);\\s*", "");
                // Replace method call with static call
                template = template.replace("entryPointClass.entryPointMethod", entryPointClassName +
                        "." + entryPointMethodName);
            } else {
                // For non-static methods, use instance-based approach
                template = template.replace("EntryPointClass entryPointClass", entryPointClassName +
                        " " + entryPointVarName);
                template = template.replace("EntryPointClass", entryPointClassName);
                template = template.replace("entryPointClass.entryPointMethod", entryPointVarName +
                        "." + entryPointMethodName);
            }
            log.debug("Generated test template for {}", entryPoint);
            return template;
        } catch (Exception e) {
            log.error("Failed to generate test template:  {}", e.getMessage(), e);
            return "// Error generating test template: " + e.getMessage();
        }
    }

    /**
     * Check if a method is static by looking up its modifiers in the JavaView.
     *
     * @param methodSig The method signature to check
     * @param view      The JavaView containing the method
     * @return true if the method is static, false otherwise
     */
    private static boolean isMethodStatic(MethodSignature methodSig, JavaView view) {
        try {
            return view.getMethod(methodSig)
                    .map(SootMethod::isStatic)
                    .orElse(false);
        } catch (Exception e) {
            log.warn("Could not determine if method is static: {}", methodSig, e);
            return false;
        }
    }

    /**
     * Read the template file from resources.
     */
    private static String readTemplateFile() throws IOException {
        try (InputStream inputStream = TestTemplateGenerator.class.getResourceAsStream(TEMPLATE_RESOURCE_PATH)) {
            if (inputStream == null) {
                throw new IOException("Template file not found in resources:  " + TEMPLATE_RESOURCE_PATH);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            log.error("Failed to read template file from resources: {}", TEMPLATE_RESOURCE_PATH, e);
            throw e;
        }
    }

    /**
     * Extract the simple class name from a fully qualified name.
     */
    private static String extractSimpleClassName(String fullyQualifiedName) {
        if (fullyQualifiedName == null || !fullyQualifiedName.contains(".")) {
            return fullyQualifiedName;
        }
        // Handle inner classes - take the part after the last $ or .
        String name = fullyQualifiedName;
        if (name.contains("$")) {
            name = name.substring(name.lastIndexOf("$") + 1);
        } else {
            name = name.substring(name.lastIndexOf(".") + 1);
        }
        return name;
    }

    /**
     * Extract the package name from a fully qualified class name.
     */
    private static String extractPackageName(String fullyQualifiedName) {
        if (fullyQualifiedName == null || !fullyQualifiedName.contains(".")) {
            return "";
        }
        // Remove inner class notation if present
        String className = fullyQualifiedName;
        if (className.contains("$")) {
            className = className.substring(0, className.indexOf("$"));
        }
        int lastDot = className.lastIndexOf(".");
        return lastDot > 0 ? className.substring(0, lastDot) : "";
    }

    /**
     * Capitalize the first letter of a string.
     */
    private static String capitalizeFirstLetter(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /**
     * Decapitalize the first letter of a string.
     */
    private static String decapitalizeFirstLetter(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }
}
