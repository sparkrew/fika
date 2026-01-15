package io.github.sparkrew.fika.api_finder;

import io.github.sparkrew.fika.api_finder.model.AnalysisResult;
import io.github.sparkrew.fika.api_finder.model.PathStats;
import io.github.sparkrew.fika.api_finder.model.ThirdPartyPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PathWriter class.
 */
@ExtendWith(MockitoExtension.class)
class PathWriterTest {

    @TempDir
    Path tempDir;

    private Path outputFile;
    private List<ThirdPartyPath> thirdPartyPaths;

    void setUp() {
        outputFile = tempDir.resolve("analysis-result.json");
        MethodSignature entryPoint = mock(MethodSignature.class);
        MethodSignature thirdPartyMethod = mock(MethodSignature.class);
        List<MethodSignature> path = Arrays.asList(entryPoint, thirdPartyMethod);
        ThirdPartyPath thirdPartyPath = new ThirdPartyPath(entryPoint, thirdPartyMethod, path, 1);
        thirdPartyPaths = new ArrayList<>();
        thirdPartyPaths.add(thirdPartyPath);
    }

    void setUpWithMocks() {
        outputFile = tempDir.resolve("analysis-result.json");
        MethodSignature entryPoint = mock(MethodSignature.class);
        ClassType entryClassType = mock(ClassType.class);
        when(entryPoint.getDeclClassType()).thenReturn(entryClassType);
        when(entryClassType.getFullyQualifiedName()).thenReturn("com.example.TestClass");
        when(entryPoint.getName()).thenReturn("publicMethod");

        MethodSignature thirdPartyMethod = mock(MethodSignature.class);
        ClassType thirdPartyClassType = mock(ClassType.class);
        when(thirdPartyMethod.getDeclClassType()).thenReturn(thirdPartyClassType);
        when(thirdPartyClassType.getFullyQualifiedName()).thenReturn("org.apache.http.HttpClient");
        when(thirdPartyMethod.getName()).thenReturn("execute");

        List<MethodSignature> path = Arrays.asList(entryPoint, thirdPartyMethod);
        ThirdPartyPath thirdPartyPath = new ThirdPartyPath(entryPoint, thirdPartyMethod, path, 1);
        thirdPartyPaths = new ArrayList<>();
        thirdPartyPaths.add(thirdPartyPath);
    }

    @AfterEach
    void tearDown() throws IOException {
        Path of = Path.of("path-stats.json");
        if (Files.exists(of)) {
            Files.delete(of);
        }
    }

    @Test
    void testWritePathStatsToJson() {
        setUp();
        List<PathStats> stats = new ArrayList<>();
        PathStats stat1 = new PathStats(
                "com.example.method1",
                "org.apache.http.execute",
                5,
                3,
                2
        );
        PathStats stat2 = new PathStats(
                "com.example.method2",
                "org.json.toString",
                3,
                2,
                1
        );
        stats.add(stat1);
        stats.add(stat2);
        PathWriter.writePathStatsToJson(stats);
        Path statsFile = Path.of("path-stats.json");
        assertTrue(Files.exists(statsFile));
    }

    @Test
    void testWritePathStatsToJson_EmptyList() {
        setUp();
        List<PathStats> emptyStats = new ArrayList<>();
        PathWriter.writePathStatsToJson(emptyStats);
        Path statsFile = Path.of("path-stats.json");
        assertTrue(Files.exists(statsFile));
    }

    @Test
    void testWriteAllFormats_CreatesOutputFile() throws IOException {
        setUpWithMocks();
        AnalysisResult result = new AnalysisResult(thirdPartyPaths);
        Path sourceRoot = tempDir.resolve("source-root");
        Path srcMainJava = sourceRoot.resolve("src").resolve("main").resolve("java");
        Files.createDirectories(srcMainJava);
        Path pomFile = sourceRoot.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0</version>
                </project>
                """;
        Files.writeString(pomFile, pomContent);
        PathWriter.writeAllFormats(result, outputFile.toString(), sourceRoot.toString(), false);
        Path fullMethodsFile = Path.of(outputFile.toString().replace(".json", "_full_methods.json"));
        assertTrue(Files.exists(fullMethodsFile), "Full methods output file should be created");
    }

    @Test
    void testWriteAllFormats_EmptyResults() throws IOException {
        setUp();
        List<ThirdPartyPath> emptyPaths = new ArrayList<>();
        AnalysisResult result = new AnalysisResult(emptyPaths);
        PathWriter.writeAllFormats(result, outputFile.toString(), null, false);
        Path fullMethodsFile = Path.of(outputFile.toString().replace(".json", "_full_methods.json"));
        assertTrue(Files.exists(fullMethodsFile));
        String content = Files.readString(fullMethodsFile);
        assertNotNull(content);
        assertFalse(content.isEmpty());
    }

    @Test
    void testWriteAllFormats_ValidatesOutputPath() {
        setUpWithMocks();
        AnalysisResult result = new AnalysisResult(thirdPartyPaths);
        String validPath = outputFile.toString();
        assertDoesNotThrow(() ->
                PathWriter.writeAllFormats(result, validPath, null, false)
        );
    }
}
