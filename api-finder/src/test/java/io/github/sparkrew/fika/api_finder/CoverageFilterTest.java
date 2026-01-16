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
        when(method.getParameterTypes()).thenReturn(List.of());
        return method;
    }

    private MethodSignature createMethodSignature2(String className) {
        MethodSignature method = mock(MethodSignature.class);
        ClassType classType = mock(ClassType.class);
        when(method.getDeclClassType()).thenReturn(classType);
        when(classType.getFullyQualifiedName()).thenReturn(className);
        when(method.getParameterTypes()).thenReturn(List.of());
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
        CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs, false);
        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute()");
        CoverageFilter.clearCache();
        assertDoesNotThrow(CoverageFilter::clearCache);
    }

    @Test
    void testRegisterTargetCall_SingleCall() {
        setupMinimalMocks();
        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute()");
        assertDoesNotThrow(() ->
                CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs, false)
        );
    }

    @Test
    void testRegisterTargetCall_MultipleCalls() {
        setupMinimalMocks();
        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute()");
        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute()");
        assertDoesNotThrow(() ->
                CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs, false)
        );
    }

    @Test
    void testIsAlreadyCoveredByTests_NoHtmlFile() {
        setupMinimalMocks();
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs, false);
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
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs, false);
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
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs, false);
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
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs, false);
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
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, constructorMethod, jacocoHtmlDirs, false);
        assertTrue(result, "Constructor call should be detected as covered");
    }

    void testIsAlreadyCoveredByTests_WithXmlReport() throws IOException {
        setupFullMocks();
        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute()");
        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute()");
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
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs, false);
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
        boolean result1 = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs, false);
        boolean result2 = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs, false);
        assertTrue(result1, "First call should find coverage");
        assertTrue(result2, "Second call should return cached result");
    }

    @Test
    void testIsAlreadyCoveredByTests_EmptyJacocoDirectories() throws IOException {
        setupMinimalMocks();
        List<File> emptyDirs = new ArrayList<>();
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, emptyDirs, false);
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
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, thirdPartyMethod, jacocoHtmlDirs, false);
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
        boolean result = CoverageFilter.isAlreadyCoveredByTests(testMethod, methodWithParams, jacocoHtmlDirs, false);
        assertTrue(result, "Should detect method call with parameters");
    }

    @Test
    void testIsAlreadyCoveredByTests_WithOverloadedMethods() throws IOException {
        MethodSignature testMethodSig = mock(MethodSignature.class);
        ClassType testClassType = mock(ClassType.class);
        when(testMethodSig.getDeclClassType()).thenReturn(testClassType);
        when(testClassType.getFullyQualifiedName()).thenReturn("com.example.TestClass");
        when(testMethodSig.getName()).thenReturn("testMethod");
        when(testMethodSig.getParameterTypes()).thenReturn(List.of());
        sootup.core.types.Type voidType = mock(sootup.core.types.Type.class);
        when(voidType.toString()).thenReturn("void");
        when(testMethodSig.getType()).thenReturn(voidType);

        MethodSignature anotherMethodSig = mock(MethodSignature.class);
        ClassType anotherClassType = mock(ClassType.class);
        when(anotherMethodSig.getDeclClassType()).thenReturn(anotherClassType);
        when(anotherClassType.getFullyQualifiedName()).thenReturn("com.example.TestClass");
        when(anotherMethodSig.getName()).thenReturn("anotherMethod");
        when(anotherMethodSig.getParameterTypes()).thenReturn(List.of());
        when(anotherMethodSig.getType()).thenReturn(voidType);

        MethodSignature executeNoParams = mock(MethodSignature.class);
        ClassType httpClientType1 = mock(ClassType.class);
        when(executeNoParams.getDeclClassType()).thenReturn(httpClientType1);
        when(httpClientType1.getFullyQualifiedName()).thenReturn("org.apache.http.HttpClient");
        when(executeNoParams.getName()).thenReturn("execute");
        when(executeNoParams.getParameterTypes()).thenReturn(List.of());

        ClassType httpRequestType = mock(ClassType.class);
        when(httpRequestType.toString()).thenReturn("org.apache.http.HttpRequest");

        MethodSignature executeWithParam = mock(MethodSignature.class);
        ClassType httpClientType2 = mock(ClassType.class);
        when(executeWithParam.getDeclClassType()).thenReturn(httpClientType2);
        when(httpClientType2.getFullyQualifiedName()).thenReturn("org.apache.http.HttpClient");
        when(executeWithParam.getName()).thenReturn("execute");
        when(executeWithParam.getParameterTypes()).thenReturn(List.of(httpRequestType));

        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute()");
        CoverageFilter.registerTargetCall("com.example.TestClass", "org.apache.http.HttpClient.execute(org.apache.http.HttpRequest)");
        Path packageDir = createPackageDirectory("com.example");
        String htmlContent = """
            <html>
            <body>
            <span id="L10" class="fc">public void testMethod() {</span>
            <span id="L11" class="fc">    client.execute();</span>
            <span id="L12" class="fc">}</span>
            <span id="L13"></span>
            <span id="L14" class="nc">public void anotherMethod() {</span>
            <span id="L15" class="nc">    client.execute(request);</span>
            <span id="L16" class="nc">}</span>
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
                        <method name="anotherMethod" desc="()V" line="14"/>
                    </class>
                    <sourcefile name="TestClass.java">
                        <line nr="10" ci="1" mi="0"/>
                        <line nr="11" ci="1" mi="0"/>
                        <line nr="12" ci="1" mi="0"/>
                        <line nr="14" ci="0" mi="1"/>
                        <line nr="15" ci="0" mi="1"/>
                        <line nr="16" ci="0" mi="1"/>
                    </sourcefile>
                </package>
            </report>
            """;
        Files.writeString(xmlFile, xmlContent);
        boolean resultNoParams = CoverageFilter.isAlreadyCoveredByTests(
                testMethodSig, executeNoParams, jacocoHtmlDirs, false);
        assertTrue(resultNoParams, "execute() without parameters should be covered");
        boolean resultWithParam = CoverageFilter.isAlreadyCoveredByTests(
                anotherMethodSig, executeWithParam, jacocoHtmlDirs, false);
        assertFalse(resultWithParam, "execute(HttpRequest) with parameter should not be covered");
    }
}