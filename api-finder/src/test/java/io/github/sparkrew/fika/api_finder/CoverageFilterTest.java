package io.github.sparkrew.fika.api_finder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.core.types.PrimitiveType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CoverageFilter class.
 */
@ExtendWith(MockitoExtension.class)
class CoverageFilterTest {

    @TempDir
    Path tempDir;

    private List<File> jacocoHtmlDirs;
    private MethodSignature testMethod;
    private MethodSignature thirdPartyMethod;
    private Path jacocoDir;

    @BeforeEach
    void setUp() throws IOException {
        CoverageFilter.clearCache();
        jacocoHtmlDirs = new ArrayList<>();
        jacocoDir = tempDir.resolve("jacoco");
        jacocoHtmlDirs.add(jacocoDir.toFile());
        testMethod = mock(MethodSignature.class);
        thirdPartyMethod = mock(MethodSignature.class);
        Path packageDir = jacocoDir.resolve("com").resolve("example");
        Files.createDirectories(packageDir);
    }

    @AfterEach
    void tearDown() {
        CoverageFilter.clearCache();
    }
    
    private void setupFullMocks() {
        testMethod = createMethodSignature("com.example.TestClass", "testMethod");
        thirdPartyMethod = createMethodSignature("org.apache.http.HttpClient", "execute");
    }

    private void setupMinimalMocks() {
        testMethod = createMethodSignature2("com.example.TestClass");
        thirdPartyMethod = createMethodSignature2("org.apache.http.HttpClient");
    }

    private MethodSignature createMethodSignature(String className, String methodName) {
        MethodSignature method = mock(MethodSignature.class);
        ClassType classType = mock(ClassType.class);
        when(method.getDeclClassType()).thenReturn(classType);
        when(classType.getFullyQualifiedName()).thenReturn(className);
        when(method.getName()).thenReturn(methodName);
        return method;
    }

    private MethodSignature createMethodSignature2(String className) {
        MethodSignature method = mock(MethodSignature.class);
        ClassType classType = mock(ClassType.class);
        when(method.getDeclClassType()).thenReturn(classType);
        when(classType.getFullyQualifiedName()).thenReturn(className);
        return method;
    }
    
    private Path createPackageDirectory(String... packageParts) throws IOException {
        Path packageDir = jacocoDir;
        for (String part : packageParts) {
            packageDir = packageDir.resolve(part);
        }
        Files.createDirectories(packageDir);
        return packageDir;
    }
    
    private void writeHtmlFile(Path packageDir, String className, String content) throws IOException {
        Path htmlFile = packageDir.resolve(className + ".java.html");
        Files.writeString(htmlFile, content);
    }

    @Test
    void testClearCache_ClearsAllCaches() {
        setupMinimalMocks();
        CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs);
        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute");
        CoverageFilter.clearCache();
        assertDoesNotThrow(CoverageFilter::clearCache);
    }

    @Test
    void testRegisterTargetCall_SingleCall() {
        setupMinimalMocks();
        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute");
        assertDoesNotThrow(() ->
                CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs)
        );
    }

    @Test
    void testRegisterTargetCall_MultipleCalls() {
        setupMinimalMocks();
        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute");
        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute");
        assertDoesNotThrow(() ->
                CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs)
        );
    }

    @Test
    void testIsAlreadyCoveredByTests_NoHtmlFile() {
        setupMinimalMocks();
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs);
        assertFalse(result, "Should return false when HTML file doesn't exist");
    }

    @Test
    void testIsAlreadyCoveredByTests_WithCoveredMethod() throws IOException {
        setupFullMocks();
        Path packageDir = createPackageDirectory("com.example");
        String htmlContent = """
                <html>
                <body>
                <span id="L1" class="fc">public void testMethod() {</span>
                <span id="L2" class="fc">    HttpClient client = new HttpClient();</span>
                <span id="L3" class="fc">    client.execute();</span>
                <span id="L4" class="fc">}</span>
                </body>
                </html>
                """;
        writeHtmlFile(packageDir, "TestClass", htmlContent);
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs);
        assertTrue(result, "Should detect covered method call");
    }

    @Test
    void testIsAlreadyCoveredByTests_WithoutCoveredMethod() throws IOException {
        setupFullMocks();
        Path packageDir = createPackageDirectory("com.example");
        String htmlContent = """
                <html>
                <body>
                <span id="L1" class="fc">public void testMethod() {</span>
                <span id="L2" class="fc">    System.out.println("Hello");</span>
                <span id="L3" class="fc">}</span>
                </body>
                </html>
                """;
        writeHtmlFile(packageDir, "TestClass", htmlContent);
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs);
        assertFalse(result, "Should return false when target method is not called");
    }

    @Test
    void testIsAlreadyCoveredByTests_WithNotCoveredLines() throws IOException {
        setupFullMocks();
        Path packageDir = createPackageDirectory("com.example");
        String htmlContent = """
                <html>
                <body>
                <span id="L1" class="fc">public void testMethod() {</span>
                <span id="L2" class="nc">    client.execute();</span>
                <span id="L3" class="fc">}</span>
                </body>
                </html>
                """;
        writeHtmlFile(packageDir, "TestClass", htmlContent);
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs);
        assertFalse(result, "Should return false when target call is not covered (nc class)");
    }

    @Test
    void testIsAlreadyCoveredByTests_WithConstructorCall() throws IOException {
        testMethod = createMethodSignature("com.example.TestClass", "testMethod");
        MethodSignature constructorMethod = createMethodSignature(
                "org.apache.http.HttpClient",
                "<init>"
        );
        Path packageDir = createPackageDirectory("com.example");
        String htmlContent = """
                <html>
                <body>
                <span id="L1" class="fc">public void testMethod() {</span>
                <span id="L2" class="fc">    HttpClient client = new HttpClient();</span>
                <span id="L3" class="fc">}</span>
                </body>
                </html>
                """;
        writeHtmlFile(packageDir, "TestClass", htmlContent);
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, constructorMethod, jacocoHtmlDirs);
        assertTrue(result, "Constructor call should be detected as covered");
    }

    void testIsAlreadyCoveredByTests_WithXmlReport() throws IOException {
        setupFullMocks();
        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute");
        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute");
        Path packageDir = createPackageDirectory("com.example");
        String htmlContent = """
                <html>
                <body>
                <span id="L10" class="fc">public void testMethod() {</span>
                <span id="L11" class="fc">    client.execute();</span>
                <span id="L12" class="fc">}</span>
                </body>
                </html>
                """;
        writeHtmlFile(packageDir, "TestClass", htmlContent);
        Path xmlFile = jacocoDir.resolve("jacoco.xml");
        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report>
                    <package name="com/example">
                        <class name="com/example/TestClass" sourcefilename="TestClass.java">
                            <method name="testMethod" desc="()V" line="10"/>
                        </class>
                        <sourcefile name="TestClass.java">
                            <line nr="10" ci="1" mi="0"/>
                            <line nr="11" ci="1" mi="0"/>
                            <line nr="12" ci="1" mi="0"/>
                        </sourcefile>
                    </package>
                </report>
                """;
        Files.writeString(xmlFile, xmlContent);
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs);
        assertTrue(result, "Should use XML report for precise checking with multiple calls");
    }

    @Test
    void testIsAlreadyCoveredByTests_CacheHit() throws IOException {
        setupFullMocks();
        Path packageDir = createPackageDirectory("com.example");
        String htmlContent = """
                <html>
                <body>
                <span id="L1" class="fc">public void testMethod() {</span>
                <span id="L2" class="fc">    client.execute();</span>
                <span id="L3" class="fc">}</span>
                </body>
                </html>
                """;
        writeHtmlFile(packageDir, "TestClass", htmlContent);
        boolean result1 = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs);
        boolean result2 = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs);
        assertTrue(result1, "First call should find coverage");
        assertTrue(result2, "Second call should return cached result");
    }

    @Test
    void testIsAlreadyCoveredByTests_EmptyJacocoDirectories() throws IOException {
        setupMinimalMocks();
        List<File> emptyDirs = new ArrayList<>();
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, emptyDirs);
        assertFalse(result, "Should return false when no JaCoCo directories provided");
    }

    @Test
    void testIsAlreadyCoveredByTests_WithPartiallyCoveredMethod() throws IOException {
        setupFullMocks();
        Path packageDir = createPackageDirectory("com.example");
        String htmlContent = """
                <html>
                <body>
                <span id="L1" class="fc bfc">public void testMethod() {</span>
                <span id="L2" class="fc bfc">    client.execute();</span>
                <span id="L3" class="fc">}</span>
                </body>
                </html>
                """;
        writeHtmlFile(packageDir, "TestClass", htmlContent);
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs);
        assertTrue(result, "Should detect partially covered method (fc bfc) as covered");
    }

    @Test
    void testIsAlreadyCoveredByTests_WithMethodParameters() throws IOException {
        testMethod = createMethodSignature("com.example.TestClass", "testMethod");
        List<sootup.core.types.Type> paramTypes = new ArrayList<>();
        paramTypes.add(PrimitiveType.getInt());
        MethodSignature methodWithParams = createMethodSignature(
                "org.apache.http.HttpClient",
                "execute"
        );
        Path packageDir = createPackageDirectory("com.example");
        String htmlContent = """
                <html>
                <body>
                <span id="L1" class="fc">public void testMethod() {</span>
                <span id="L2" class="fc">    client.execute(123);</span>
                <span id="L3" class="fc">}</span>
                </body>
                </html>
                """;
        writeHtmlFile(packageDir, "TestClass", htmlContent);
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, methodWithParams, jacocoHtmlDirs);
        assertTrue(result, "Should detect method call with parameters");
    }
}
