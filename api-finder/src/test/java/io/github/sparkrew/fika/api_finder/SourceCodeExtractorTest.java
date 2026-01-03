package io.github.sparkrew.fika.api_finder;

import io.github.sparkrew.fika.api_finder.model.ClassMemberData;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SourceCodeExtractor class.
 */
@ExtendWith(MockitoExtension.class)
class SourceCodeExtractorTest {

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
    void testExtractMethodFromSource_ClassNotFound() throws IOException {
        MethodSignature methodSig = mock(MethodSignature.class);
        ClassType classType = mock(ClassType.class);
        when(methodSig.getDeclClassType()).thenReturn(classType);
        when(classType.getFullyQualifiedName()).thenReturn("com.example.NonExistent");
        when(methodSig.getName()).thenReturn("testMethod");

        Path packageDir = srcMainJava.resolve("com").resolve("example");
        Files.createDirectories(packageDir);
        Path javaFile = packageDir.resolve("OtherClass.java");
        String javaContent = """
                package com.example;
                                
                public class OtherClass {
                    public void otherMethod() {
                        System.out.println("Hello");
                    }
                }
                """;
        Files.writeString(javaFile, javaContent);
        String result = SourceCodeExtractor.extractMethodFromSource(
                methodSig,
                sourceRoot.toString(),
                null
        );
        assertNull(result);
    }

    @Test
    void testExtractMethodFromSource_MethodFound() throws IOException {
        MethodSignature methodSig = mock(MethodSignature.class);
        ClassType classType = mock(ClassType.class);
        when(methodSig.getDeclClassType()).thenReturn(classType);
        when(classType.getFullyQualifiedName()).thenReturn("com.example.TestClass");
        when(methodSig.getName()).thenReturn("testMethod");

        Path packageDir = srcMainJava.resolve("com").resolve("example");
        Files.createDirectories(packageDir);
        Path javaFile = packageDir.resolve("TestClass.java");
        String javaContent = """
                package com.example;
                                
                public class TestClass {
                    public void testMethod() {
                        System.out.println("Test method");
                    }
                }
                """;
        Files.writeString(javaFile, javaContent);
        String result = SourceCodeExtractor.extractMethodFromSource(
                methodSig,
                sourceRoot.toString(),
                null
        );
        assertNotNull(result);
        assertTrue(result.contains("testMethod"));
    }

    @Test
    void testExtractMethodFromSource_CacheHit() throws IOException {
        MethodSignature methodSig = mock(MethodSignature.class);
        ClassType classType = mock(ClassType.class);
        when(methodSig.getDeclClassType()).thenReturn(classType);
        when(classType.getFullyQualifiedName()).thenReturn("com.example.TestClass");
        when(methodSig.getName()).thenReturn("testMethod");
        when(methodSig.toString()).thenReturn("com.example.TestClass.testMethod()");

        Path packageDir = srcMainJava.resolve("com").resolve("example");
        Files.createDirectories(packageDir);
        Path javaFile = packageDir.resolve("TestClass.java");
        String javaContent = """
                package com.example;
                                
                public class TestClass {
                    public void testMethod() {
                        System.out.println("Test method");
                    }
                }
                """;
        Files.writeString(javaFile, javaContent);
        String result1 = SourceCodeExtractor.extractMethodFromSource(
                methodSig,
                sourceRoot.toString(),
                null
        );
        String result2 = SourceCodeExtractor.extractMethodFromSource(
                methodSig,
                sourceRoot.toString(),
                null
        );
        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals(result1, result2);
    }

    @Test
    void testExtractClassMembers_ClassNotFound() {
        MethodSignature methodSig = mock(MethodSignature.class);
        ClassType classType = mock(ClassType.class);
        when(methodSig.getDeclClassType()).thenReturn(classType);
        when(classType.getFullyQualifiedName()).thenReturn("com.example.NonExistent");
        ClassMemberData result = SourceCodeExtractor.extractClassMembers(
                methodSig,
                sourceRoot.toString()
        );
        assertNotNull(result);
        assertTrue(result.constructors().isEmpty());
        assertTrue(result.setters().isEmpty());
        assertTrue(result.getters().isEmpty());
        assertTrue(result.imports().isEmpty());
    }


    @Test
    void testGetModel_ReturnsSameModelForSamePath() throws IOException {
        Path packageDir = srcMainJava.resolve("com").resolve("example");
        Files.createDirectories(packageDir);
        Path javaFile = packageDir.resolve("TestClass.java");
        String javaContent = """
                package com.example;
                                
                public class TestClass {
                    public void method() {}
                }
                """;
        Files.writeString(javaFile, javaContent);
        var model1 = SourceCodeExtractor.getModel(sourceRoot.toString());
        var model2 = SourceCodeExtractor.getModel(sourceRoot.toString());
        assertNotNull(model1);
        assertNotNull(model2);
        assertSame(model1, model2);
    }

    @Test
    void testCountMethodInvocations_SingleInvocation() throws IOException {
        // Create caller class
        Path packageDir = srcMainJava.resolve("com").resolve("example");
        Files.createDirectories(packageDir);
        Path callerFile = packageDir.resolve("Caller.java");
        String callerContent = """
                package com.example;
                                
                public class Caller {
                    public void callerMethod() {
                        Target target = new Target();
                        target.targetMethod();
                    }
                }
                """;
        Files.writeString(callerFile, callerContent);

        MethodSignature callerSig = mock(MethodSignature.class);
        ClassType callerClass = mock(ClassType.class);
        when(callerSig.getDeclClassType()).thenReturn(callerClass);
        when(callerClass.getFullyQualifiedName()).thenReturn("com.example.Caller");
        when(callerSig.getName()).thenReturn("callerMethod");
        when(callerSig.toString()).thenReturn("com.example.Caller.callerMethod()");

        MethodSignature targetSig = mock(MethodSignature.class);
        ClassType targetClass = mock(ClassType.class);
        when(targetSig.getDeclClassType()).thenReturn(targetClass);
        when(targetClass.getFullyQualifiedName()).thenReturn("com.example.Target");
        when(targetSig.getName()).thenReturn("targetMethod");
        when(targetSig.toString()).thenReturn("com.example.Target.targetMethod()");

        int count = SourceCodeExtractor.countMethodInvocations(callerSig, targetSig, sourceRoot.toString());
        assertEquals(1, count);
    }

    @Test
    void testCountMethodInvocations_MultipleInvocations() throws IOException {
        Path packageDir = srcMainJava.resolve("com").resolve("example");
        Files.createDirectories(packageDir);
        Path callerFile = packageDir.resolve("Caller.java");
        String callerContent = """
                package com.example;
                                
                public class Caller {
                    public void callerMethod() {
                        Target target = new Target();
                        target.targetMethod();
                        target.targetMethod();
                        target.targetMethod();
                    }
                }
                """;
        Files.writeString(callerFile, callerContent);

        MethodSignature callerSig = mock(MethodSignature.class);
        ClassType callerClass = mock(ClassType.class);
        when(callerSig.getDeclClassType()).thenReturn(callerClass);
        when(callerClass.getFullyQualifiedName()).thenReturn("com.example.Caller");
        when(callerSig.getName()).thenReturn("callerMethod");
        when(callerSig.toString()).thenReturn("com.example.Caller.callerMethod()");

        MethodSignature targetSig = mock(MethodSignature.class);
        ClassType targetClass = mock(ClassType.class);
        when(targetSig.getDeclClassType()).thenReturn(targetClass);
        when(targetClass.getFullyQualifiedName()).thenReturn("com.example.Target");
        when(targetSig.getName()).thenReturn("targetMethod");
        when(targetSig.toString()).thenReturn("com.example.Target.targetMethod()");

        int count = SourceCodeExtractor.countMethodInvocations(callerSig, targetSig, sourceRoot.toString());
        assertEquals(3, count);
    }

    @Test
    void testCountMethodInvocations_CacheHit() throws IOException {
        Path packageDir = srcMainJava.resolve("com").resolve("example");
        Files.createDirectories(packageDir);
        Path callerFile = packageDir.resolve("Caller.java");
        String callerContent = """
                package com.example;
                                
                public class Caller {
                    public void callerMethod() {
                        Target target = new Target();
                        target.targetMethod();
                        target.targetMethod();
                    }
                }
                """;
        Files.writeString(callerFile, callerContent);

        MethodSignature callerSig = mock(MethodSignature.class);
        ClassType callerClass = mock(ClassType.class);
        when(callerSig.getDeclClassType()).thenReturn(callerClass);
        when(callerClass.getFullyQualifiedName()).thenReturn("com.example.Caller");
        when(callerSig.getName()).thenReturn("callerMethod");
        when(callerSig.toString()).thenReturn("com.example.Caller.callerMethod()");

        MethodSignature targetSig = mock(MethodSignature.class);
        ClassType targetClass = mock(ClassType.class);
        when(targetSig.getDeclClassType()).thenReturn(targetClass);
        when(targetClass.getFullyQualifiedName()).thenReturn("com.example.Target");
        when(targetSig.getName()).thenReturn("targetMethod");
        when(targetSig.toString()).thenReturn("com.example.Target.targetMethod()");
        // First call - should compute
        int count1 = SourceCodeExtractor.countMethodInvocations(callerSig, targetSig, sourceRoot.toString());
        // Second call - should use cache
        int count2 = SourceCodeExtractor.countMethodInvocations(callerSig, targetSig, sourceRoot.toString());
        assertEquals(2, count1);
        assertEquals(2, count2);
        assertEquals(count1, count2);
    }
}
