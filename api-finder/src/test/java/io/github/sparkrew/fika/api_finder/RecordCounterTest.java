package io.github.sparkrew.fika.api_finder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RecordCounter class.
 */
@ExtendWith(MockitoExtension.class)
class RecordCounterTest {

    @TempDir
    Path tempDir;

    private Path sourceRoot;
    private Path srcMainJava;

    @BeforeEach
    void setUp() throws IOException {
        SourceCodeExtractor.clearCaches();
        RecordCounter.clearCache();
        sourceRoot = tempDir.resolve("test-project");
        srcMainJava = sourceRoot.resolve("src").resolve("main").resolve("java");
        Files.createDirectories(srcMainJava);
        Path pomFile = sourceRoot.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0-SNAPSHOT</version>
                    <properties>
                        <maven.compiler.source>17</maven.compiler.source>
                        <maven.compiler.target>17</maven.compiler.target>
                    </properties>
                </project>
                """;
        Files.writeString(pomFile, pomContent);
    }

    @AfterEach
    void tearDown() {
        SourceCodeExtractor.clearCaches();
        RecordCounter.clearCache();
    }

    @Test
    void testCountConditionsInPath_EmptyPath() {
        List<MethodSignature> emptyPath = Collections.emptyList();
        int count = RecordCounter.countConditionsInPath(emptyPath, sourceRoot.toString());
        assertEquals(0, count);
    }

    @Test
    void testCountConditionsInPath_NullPath() {
        int count = RecordCounter.countConditionsInPath(null, sourceRoot.toString());
        assertEquals(0, count);
    }

    @Test
    void testCountConditionsInPath_NullSourceRoot() {
        MethodSignature method = mock(MethodSignature.class);
        List<MethodSignature> path = Collections.singletonList(method);
        assertThrows(NullPointerException.class, () -> RecordCounter.countConditionsInPath(path, null));
    }

    @Test
    void testCountConditionsInPath_WithSimpleMethod() throws IOException {
        MethodSignature method = mock(MethodSignature.class);
        ClassType classType = mock(ClassType.class);
        when(method.getDeclClassType()).thenReturn(classType);
        when(classType.getFullyQualifiedName()).thenReturn("com.example.TestClass");
        when(method.getName()).thenReturn("simpleMethod");
        List<MethodSignature> path = Collections.singletonList(method);
        Path packageDir = srcMainJava.resolve("com").resolve("example");
        Files.createDirectories(packageDir);
        Path javaFile = packageDir.resolve("TestClass.java");
        String javaContent = """
                package com.example;
                                
                public class TestClass {
                    public void simpleMethod() {
                        System.out.println("Simple");
                    }
                }
                """;
        Files.writeString(javaFile, javaContent);
        int count = RecordCounter.countConditionsInPath(path, sourceRoot.toString());
        assertEquals(0, count);
    }

    @Test
    void testCountConditionsInPath_WithConditionalMethod() throws IOException {
        MethodSignature method = mock(MethodSignature.class);
        ClassType classType = mock(ClassType.class);
        when(method.getDeclClassType()).thenReturn(classType);
        when(classType.getFullyQualifiedName()).thenReturn("com.example.TestClass");
        when(method.getName()).thenReturn("conditionalMethod");
        List<MethodSignature> path = Collections.singletonList(method);
        Path packageDir = srcMainJava.resolve("com").resolve("example");
        Files.createDirectories(packageDir);
        Path javaFile = packageDir.resolve("TestClass.java");
        String javaContent = """
                package com.example;
                                
                public class TestClass {
                    public void conditionalMethod() {
                        if (true) {
                            System.out.println("True");
                        }
                        for (int i = 0; i < 10; i++) {
                            System.out.println(i);
                        }
                    }
                }
                """;
        Files.writeString(javaFile, javaContent);
        int count = RecordCounter.countConditionsInPath(path, sourceRoot.toString());
        assertEquals(2, count, "Should count at least the if and for conditions");
    }

    @Test
    void testCountConditionsInPath_MultipleMethods() throws IOException {
        MethodSignature method1 = mock(MethodSignature.class);
        ClassType classType1 = mock(ClassType.class);
        when(method1.getDeclClassType()).thenReturn(classType1);
        when(classType1.getFullyQualifiedName()).thenReturn("com.example.TestClass");
        when(method1.getName()).thenReturn("method1");

        MethodSignature method2 = mock(MethodSignature.class);
        ClassType classType2 = mock(ClassType.class);
        when(method2.getDeclClassType()).thenReturn(classType2);
        when(classType2.getFullyQualifiedName()).thenReturn("com.example.TestClass");
        when(method2.getName()).thenReturn("method2");

        List<MethodSignature> path = Arrays.asList(method1, method2);
        Path packageDir = srcMainJava.resolve("com").resolve("example");
        Files.createDirectories(packageDir);
        Path javaFile = packageDir.resolve("TestClass.java");
        String javaContent = """
                package com.example;
                                
                public class TestClass {
                    public void method1() {
                        if (true) {
                            System.out.println("Method 1");
                        }
                    }
                    
                    public void method2() {
                        while (false) {
                            System.out.println("Method 2");
                        }
                    }
                }
                """;
        Files.writeString(javaFile, javaContent);
        int count = RecordCounter.countConditionsInPath(path, sourceRoot.toString());
        assertEquals(2, count, "Should count conditions from both methods");
    }

    @Test
    void testCountConditionsInPath_NonExistentClass() {
        MethodSignature method = mock(MethodSignature.class);
        ClassType classType = mock(ClassType.class);
        when(method.getDeclClassType()).thenReturn(classType);
        when(classType.getFullyQualifiedName()).thenReturn("com.example.NonExistent");
        when(method.getName()).thenReturn("method");
        List<MethodSignature> path = Collections.singletonList(method);
        int count = RecordCounter.countConditionsInPath(path, sourceRoot.toString());
        assertEquals(0, count);
    }

    @Test
    void testCountConditionsInPath_CachingBehavior() throws IOException {
        MethodSignature method = mock(MethodSignature.class);
        ClassType classType = mock(ClassType.class);
        when(method.getDeclClassType()).thenReturn(classType);
        when(classType.getFullyQualifiedName()).thenReturn("com.example.TestClass");
        when(method.getName()).thenReturn("cachedMethod");
        when(method.toString()).thenReturn("com.example.TestClass.cachedMethod()");
        List<MethodSignature> path = Collections.singletonList(method);

        Path packageDir = srcMainJava.resolve("com").resolve("example");
        Files.createDirectories(packageDir);
        Path javaFile = packageDir.resolve("TestClass.java");
        String javaContent = """
                package com.example;
                                
                public class TestClass {
                    public void cachedMethod() {
                        if (true) {
                            System.out.println("Cached");
                        }
                    }
                }
                """;
        Files.writeString(javaFile, javaContent);
        int count1 = RecordCounter.countConditionsInPath(path, sourceRoot.toString());
        int count2 = RecordCounter.countConditionsInPath(path, sourceRoot.toString());
        assertEquals(count1, count2);
    }
}
