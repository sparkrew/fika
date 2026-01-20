package io.github.sparkrew.fika.api_finder;

import io.github.sparkrew.fika.api_finder.model.ThirdPartyPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.signatures.MethodSignature;

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
     * @return The filled template with replaced placeholders
     */
    public static String generateTestTemplate(ThirdPartyPath thirdPartyPath) {
        try {
            // We have a template in the resources' folder. It has basic boilerplate code with placeholders for a JUnit test.
            String template = readTemplateFile();
            MethodSignature entryPoint = thirdPartyPath.entryPoint();
            MethodSignature thirdPartyMethod = thirdPartyPath.thirdPartyMethod();
            String entryPointClassName = extractSimpleClassName(entryPoint.getDeclClassType().getFullyQualifiedName());
            String thirdPartyClassName = extractSimpleClassName(thirdPartyMethod.getDeclClassType().getFullyQualifiedName());
            String entryPointPackage = extractPackageName(entryPoint.getDeclClassType().getFullyQualifiedName());
            String entryPointMethodName = sanitizeMethodName(entryPoint.getName());
            String thirdPartyMethodName = sanitizeMethodName(thirdPartyMethod.getName());
            // Get the second-to-last method in the path
            MethodSignature lastPathMethod = thirdPartyPath.path().size() < 2 ? thirdPartyMethod : thirdPartyPath.path().get(thirdPartyPath.path().size() - 2);
            String lastMethodClassName = extractSimpleClassName(lastPathMethod.getDeclClassType().getFullyQualifiedName());
            String lastMethodName = sanitizeMethodName(lastPathMethod.getName());
            // Build test class name based on path size
            String testClassName;
            if (lastMethodClassName.equals(entryPointClassName)) {
                testClassName = lastMethodClassName + lastMethodName + "_" + thirdPartyClassName + thirdPartyMethodName + "FikaTest";
            } else {
                testClassName = entryPointClassName + "_" + lastMethodClassName + lastMethodName + "_" + thirdPartyClassName + thirdPartyMethodName + "FikaTest";
            }
            String testMethodName = "test" + capitalizeFirstLetter(entryPointMethodName);
            // Here, we replace placeholders in the template.
            template = template.replace("packagename", entryPointPackage);
            template = template.replace("TestNameTest", testClassName);
            template = template.replace("testname()", testMethodName + "()");
            log.debug("Generated test template for {}", entryPoint);
            return template;
        } catch (Exception e) {
            log.error("Failed to generate test template:  {}", e.getMessage(), e);
            return "// Error generating test template: " + e.getMessage();
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
        if (fullyQualifiedName == null) {
            return null;
        }
        // Handle inner classes - take the part after the last $ or .
        String name = fullyQualifiedName;
        if (name.contains("$")) {
            name = name.substring(name.lastIndexOf("$") + 1);
        } else if (name.contains(".")) {
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
     * Sanitize method names by removing special method identifiers like <init> and <clinit>.
     */
    private static String sanitizeMethodName(String methodName) {
        if (methodName == null) {
            return "";
        }
        // Remove <init> and <clinit> from method names
        String sanitized = methodName.replace("<init>", "").replace("<clinit>", "");
        // If the result is empty after sanitization, use a default name
        return sanitized.isEmpty() ? "method" : sanitized;
    }
}
