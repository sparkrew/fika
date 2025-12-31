package io.github.sparkrew.fika.api_finder.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import sootup.core.signatures.MethodSignature;
import spoon.MavenLauncher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SpoonMethodFinder
 */
@ExtendWith(MockitoExtension.class)
class SpoonMethodFinderTest {

    @TempDir
    Path tempDir;

    private CtModel spoonModel;

    @BeforeEach
    void setUp() throws IOException {
        Path sourceRoot = tempDir.resolve("test-project");
        Path srcMainJava = sourceRoot.resolve("src").resolve("main").resolve("java");
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
        Path packageDir = srcMainJava.resolve("com").resolve("example");
        Files.createDirectories(packageDir);
        Path javaFile = packageDir.resolve("TestClass.java");
        String javaContent = """
                package com.example;
                                
                public class TestClass {
                    public TestClass() {
                        // Default constructor
                    }
                    
                    public TestClass(String name) {
                        // Constructor with parameter
                    }
                    
                    public void simpleMethod() {
                        System.out.println("Simple");
                    }
                    
                    public void overloadedMethod() {
                        System.out.println("No params");
                    }
                    
                    public void overloadedMethod(String param) {
                        System.out.println("With param: " + param);
                    }
                }
                """;
        Files.writeString(javaFile, javaContent);
        MavenLauncher launcher = new MavenLauncher(sourceRoot.toString(),
                MavenLauncher.SOURCE_TYPE.APP_SOURCE);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().disableConsistencyChecks();
        spoonModel = launcher.buildModel();
    }

    @Test
    void testFindType_ExistingClass() {
        CtType<?> type = SpoonMethodFinder.findType(spoonModel, "com.example.TestClass");
        assertNotNull(type);
        assertEquals("TestClass", type.getSimpleName());
        assertEquals("com.example.TestClass", type.getQualifiedName());
    }

    @Test
    void testFindType_NonExistingClass() {
        CtType<?> type = SpoonMethodFinder.findType(spoonModel, "com.example.NonExistent");
        assertNull(type);
    }

    @Test
    void testFindTypeCached_NonExistentClassCached() {
        CtType<?> type1 = SpoonMethodFinder.findTypeCached(spoonModel, "com.example.NonExistent");
        CtType<?> type2 = SpoonMethodFinder.findTypeCached(spoonModel, "com.example.NonExistent");
        assertNull(type1);
        assertNull(type2);
    }

    @Test
    void testFindRegularMethod_NonExistingMethod() {
        CtType<?> type = SpoonMethodFinder.findType(spoonModel, "com.example.TestClass");
        assertNotNull(type);
        MethodSignature methodSig = mock(MethodSignature.class);
        CtMethod<?> method = SpoonMethodFinder.findRegularMethod(type, "nonExistentMethod", methodSig);
        assertNull(method);
    }

    @Test
    void testFindRegularMethod_OverloadedMethod_NoParams() {
        CtType<?> type = SpoonMethodFinder.findType(spoonModel, "com.example.TestClass");
        assertNotNull(type);
        MethodSignature methodSig = mock(MethodSignature.class);
        when(methodSig.getParameterTypes()).thenReturn(java.util.List.of());
        CtMethod<?> method = SpoonMethodFinder.findRegularMethod(type, "overloadedMethod", methodSig);
        assertNotNull(method);
        assertEquals("overloadedMethod", method.getSimpleName());
        assertEquals(0, method.getParameters().size());
    }

    @Test
    void testFindConstructor_DefaultConstructor() {
        CtType<?> type = SpoonMethodFinder.findType(spoonModel, "com.example.TestClass");
        assertNotNull(type);
        MethodSignature methodSig = mock(MethodSignature.class);
        when(methodSig.getParameterTypes()).thenReturn(java.util.List.of());
        var constructor = SpoonMethodFinder.findConstructor(type, methodSig);
        assertNotNull(constructor);
        assertEquals(0, constructor.getParameters().size());
    }

    @Test
    void testFindType_EmptyClassName() {
        CtType<?> type = SpoonMethodFinder.findType(spoonModel, "");
        assertNull(type);
    }

    @Test
    void testFindRegularMethod_UniqueMethod() {
        CtType<?> type = SpoonMethodFinder.findType(spoonModel, "com.example.TestClass");
        assertNotNull(type);
        MethodSignature methodSig = mock(MethodSignature.class);
//        when(methodSig.getParameterTypes()).thenReturn(java.util.List.of());
        CtMethod<?> method = SpoonMethodFinder.findRegularMethod(type, "simpleMethod", methodSig);
        assertNotNull(method);
        assertEquals("simpleMethod", method.getSimpleName());
    }
}
