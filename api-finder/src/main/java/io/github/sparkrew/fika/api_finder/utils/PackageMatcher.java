package io.github.sparkrew.fika.api_finder.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * This class provides methods to match package names with Maven coordinates.
 * It finds the best matching Maven coordinate based on the number of segments
 * that match between the package name and the Maven coordinates.
 */
public class PackageMatcher {

    private static final Logger log = LoggerFactory.getLogger(PackageMatcher.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Map<String, List<String>> dependencyMap = new HashMap<>();
    private static final List<String> ignoredPrefixes = Arrays.asList(
            "java.", "jdk.", "sun.", "com.sun."
    );
    private static boolean loaded = false;
    private static final String DEFAULT_IGNORED_FILE = "ignored_packages.txt";

    /**
     * Returns the Maven coordinates for a given package name.
     * The coordinates are in the format "groupId:artifactId:version".
     * If the package name is not found, it returns null.
     *
     * @param packageName The package name to look up.
     * @param packageMap  The path to the package map file.
     * @return The Maven coordinates or null if not found.
     */
    public static String getDependencyName(String packageName, Path packageMap) {
        if (!loaded) {
            loadDependencyMap(packageMap);
        }
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        List<String> packageNames = dependencyMap.get(packageName);
        if (packageNames == null || packageNames.isEmpty()) {
            return null;
        }
        String dependency = packageNames.get(0);
        if (dependency != null) {
            String[] parts = dependency.split(":");
            if (parts.length >= 4) {
                return parts[0] + ":" + parts[1] + ":" + parts[3];
            } else {
                log.error("Invalid dependency format for package '{}': {}", packageName, dependency);
                return dependency;
            }
        }
        log.error("Package '{}' not found in the dependency map. ", packageName);
        return null;
    }

    private static void loadDependencyMap(Path packageMap) {
        try {
            if (packageMap == null || !Files.exists(packageMap)) {
                log.warn("Package map file does not exist: {}", packageMap);
                return;
            }
            try (InputStream inputStream = Files.newInputStream(packageMap)) {
                Map<String, List<String>> loadedMap = objectMapper.readValue(inputStream, new TypeReference<>() {
                });
                dependencyMap.putAll(loadedMap);
                log.info("Successfully loaded package dependency map from: {}", packageMap);
            }
        } catch (IOException e) {
            log.error("Error reading package-dependency-map from file: {}", packageMap, e);
        }
        loaded = true;
    }

    /**
     * Loads ignored package prefixes from the default resource file and adds the given package name.
     *
     * @param packageName The package name to add to the ignored prefixes.
     * @return A set of ignored package prefixes.
     */
    public static Set<String> loadIgnoredPrefixes(String packageName) {
        Set<String> prefixes = new HashSet<>(ignoredPrefixes);
        if (packageName != null && !packageName.trim().isEmpty()) {
            String normalizedPackage = packageName.trim();
            if (!normalizedPackage.endsWith(".")) {
                normalizedPackage += ".";
            }
            prefixes.add(normalizedPackage);
        }
        try (InputStream in = PackageMatcher.class.getClassLoader().getResourceAsStream(DEFAULT_IGNORED_FILE)) {
            if (in != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                    reader.lines()
                            .map(String::trim)
                            // # is to allow comments, not that it is important
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .forEach(prefixes::add);
                }
            } else {
                log.warn("Ignored packages file not found: {}", DEFAULT_IGNORED_FILE);
            }
        } catch (IOException e) {
            log.error("Error reading ignored packages file: {}", DEFAULT_IGNORED_FILE, e);
        }
        return prefixes;
    }

    /**
     * Checks if the dependency map contains the given package name.
     *
     * @param packageName The package name to check.
     * @param packageMap  The path to the package map file.
     * @return true if the package name exists in the dependency map, false otherwise.
     */
    public static boolean containsPackage(String packageName, Path packageMap) {
        if (!loaded) {
            loadDependencyMap(packageMap);
        }
        if (!loaded || dependencyMap.isEmpty()) {
            log.warn("Dependency map not loaded or empty when checking for package: {}", packageName);
            return false;
        }
        return dependencyMap.containsKey(packageName);
    }

}

