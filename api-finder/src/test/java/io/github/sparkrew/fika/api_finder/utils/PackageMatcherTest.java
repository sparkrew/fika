package io.github.sparkrew.fika.api_finder.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PackageMatcher
 */
class PackageMatcherTest {

    @TempDir
    Path tempDir;

    private Path packageMapFile;

    @BeforeEach
    void setUp() throws IOException {
        packageMapFile = tempDir.resolve("package-map.json");
        String jsonContent = """
                {
                    "com.example.test": ["com.example:test-lib:jar:1.0.0"],
                    "org.apache.commons.lang3": ["org.apache.commons:commons-lang3:jar:3.12.0"],
                    "com.google.common": ["com.google.guava:guava:jar:31.0-jre"]
                }
                """;
        Files.writeString(packageMapFile, jsonContent);
    }

    @AfterEach
    void tearDown() {
        // Clear any cache if it causes errors. We can do this later if needed.
    }

    @Test
    void testLoadIgnoredPrefixes_WithValidPackageName() {
        String packageName = "com.example.myapp";
        Set<String> prefixes = PackageMatcher.loadIgnoredPrefixes(packageName);
        assertNotNull(prefixes);
        assertFalse(prefixes.isEmpty());
        assertTrue(prefixes.contains("com.example.myapp."));
        assertTrue(prefixes.contains("java."));
        assertTrue(prefixes.contains("jdk."));
        assertTrue(prefixes.contains("sun."));
        assertTrue(prefixes.contains("com.sun."));
    }

    @Test
    void testLoadIgnoredPrefixes_WithNullPackageName() {
        Set<String> prefixes = PackageMatcher.loadIgnoredPrefixes(null);
        assertNotNull(prefixes);
        assertFalse(prefixes.isEmpty());
        assertTrue(prefixes.contains("java."));
        assertFalse(prefixes.stream().anyMatch(p -> p.equals("null.") || p.equals(".")));
    }

    @Test
    void testLoadIgnoredPrefixes_WithEmptyPackageName() {
        Set<String> prefixes = PackageMatcher.loadIgnoredPrefixes("");
        assertNotNull(prefixes);
        assertFalse(prefixes.isEmpty());
        assertTrue(prefixes.contains("java."));
    }

    @Test
    void testLoadIgnoredPrefixes_WithPackageNameWithoutTrailingDot() {
        String packageName = "com.example.test";
        Set<String> prefixes = PackageMatcher.loadIgnoredPrefixes(packageName);
        assertTrue(prefixes.contains("com.example.test."));
    }

    @Test
    void testLoadIgnoredPrefixes_WithPackageNameWithTrailingDot() {
        String packageName = "com.example.test.";
        Set<String> prefixes = PackageMatcher.loadIgnoredPrefixes(packageName);
        assertTrue(prefixes.contains("com.example.test."));
    }

    @Test
    void testGetDependencyName_WithExistingPackage() {
        String packageName = "com.example.test";
        String dependency = PackageMatcher.getDependencyName(packageName, packageMapFile);
        assertNotNull(dependency);
        assertEquals("com.example:test-lib:1.0.0", dependency);
    }

    @Test
    void testGetDependencyName_WithNonExistingPackage() {
        String packageName = "com.nonexistent.package";
        String dependency = PackageMatcher.getDependencyName(packageName, packageMapFile);
        assertNull(dependency);
    }

    @Test
    void testGetDependencyName_WithNullPackage() {
        String dependency = PackageMatcher.getDependencyName(null, packageMapFile);
        assertNull(dependency);
    }

    @Test
    void testGetDependencyName_WithEmptyPackage() {
        String dependency = PackageMatcher.getDependencyName("", packageMapFile);
        assertNull(dependency);
    }

    @Test
    void testGetDependencyName_MultipleCallsCacheResult() {
        String packageName = "org.apache.commons.lang3";
        String dependency1 = PackageMatcher.getDependencyName(packageName, packageMapFile);
        String dependency2 = PackageMatcher.getDependencyName(packageName, packageMapFile);
        assertNotNull(dependency1);
        assertNotNull(dependency2);
        assertEquals(dependency1, dependency2);
        assertEquals("org.apache.commons:commons-lang3:3.12.0", dependency1);
    }

    @Test
    void testContainsPackage_WithExistingPackage() {
        String packageName = "com.example.test";
        PackageMatcher.getDependencyName(packageName, packageMapFile);
        boolean contains = PackageMatcher.containsPackage(packageName, packageMapFile);
        assertTrue(contains);
    }

    @Test
    void testContainsPackage_WithNonExistingPackage() {
        String packageName = "com.nonexistent.package";
        PackageMatcher.getDependencyName("com.example.test", packageMapFile);
        boolean contains = PackageMatcher.containsPackage(packageName, packageMapFile);
        assertFalse(contains);
    }

    @Test
    void testGetDependencyName_WithComplexMavenCoordinates() {
        String packageName = "com.google.common";
        String dependency = PackageMatcher.getDependencyName(packageName, packageMapFile);
        assertNotNull(dependency);
        assertEquals("com.google.guava:guava:31.0-jre", dependency);
    }
}
