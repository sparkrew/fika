package io.github.sparkrew.fika.api_finder;

import io.github.sparkrew.fika.api_finder.utils.CoverageLogger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.Type;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class CoverageFilter {

    private static final Logger log = LoggerFactory.getLogger(CoverageFilter.class);
    // Cache - Map<htmlFilePath, Map<thirdPartyMethod, isCovered>>
    private static final Map<String, Map<String, Boolean>> coverageCache = new ConcurrentHashMap<>();
    // Cache for parsed HTML documents: Map<htmlFilePath, Set<coveredMethods>>
    private static final Map<String, Set<String>> parsedHtmlCache = new ConcurrentHashMap<>();

    /**
     * Clears the coverage cache. Call this if you want to force re-parsing of HTML files.
     */
    public static void clearCache() {
        coverageCache.clear();
        parsedHtmlCache.clear();
        log.debug("Coverage cache cleared");
    }

    /**
     * Checks if a given method is covered by tests using JaCoCo HTML reports.
     *
     * @param method         The method signature to check for coverage
     * @param target         The target third-party method signature
     * @param jacocoHtmlDirs List of JaCoCo HTML report directories (site/jacoco roots)
     * @return true if the method is covered by tests, false otherwise
     */
    public static boolean isAlreadyCoveredByTests(MethodSignature method, MethodSignature target,
                                                  List<File> jacocoHtmlDirs) {
        try {
            // Extract class and method information from the method signature
            String fullClassName = method.getDeclClassType().getFullyQualifiedName();
            String simpleClassName = fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
            String packageName = fullClassName.substring(0, fullClassName.lastIndexOf('.'));
            String thirdPartyMethod = target.getDeclClassType().getFullyQualifiedName() + "."
                    + target.getName();
            String thirdPartyMethodFull = target.getDeclClassType().getFullyQualifiedName()
                    + "." + target.getName()
                    + "(" + target.getParameterTypes().stream()
                    .map(Type::toString)
                    .collect(Collectors.joining(", ")) + ")";
            // Search through all JaCoCo report directories
            for (File dir : jacocoHtmlDirs) {
                File htmlFile = dir.toPath()
                        .resolve(packageName)
                        .resolve(simpleClassName + ".java.html")
                        .toFile();
                if (!htmlFile.exists()) {
                    continue;
                }
                String htmlFilePath = htmlFile.getAbsolutePath();
                // Check cache first
                Map<String, Boolean> fileCache = coverageCache.get(htmlFilePath);
                if (fileCache != null && fileCache.containsKey(thirdPartyMethod)) {
                    log.debug("Cache hit for {} in {}", thirdPartyMethod, htmlFilePath);
                    return fileCache.get(thirdPartyMethod);
                }
                // Not in cache, need to check
                boolean isCovered = isMethodCovered(htmlFile, thirdPartyMethod);
                // Store in cache
                coverageCache.computeIfAbsent(htmlFilePath, k -> new ConcurrentHashMap<>())
                        .put(thirdPartyMethod, isCovered);
                if (isCovered) {
                    CoverageLogger.logCoverage(thirdPartyMethodFull, true);
                    return true;
                }
            }
            CoverageLogger.logCoverage(thirdPartyMethodFull, false);
            return false;
        } catch (Exception e) {
            log.error("Error checking coverage for method: {}", method.getName(), e);
            return false;
        }
    }

    /**
     * Parses the JaCoCo HTML report to determine if a method is covered.
     *
     * @param htmlFile       The JaCoCo HTML file for the class
     * @param thirdPartyMethod     The method name to check
     * @return true if the method is covered (fully or partially), false otherwise
     * @throws Exception if parsing fails
     */
    private static boolean isMethodCovered(File htmlFile, String thirdPartyMethod) throws Exception {
        String htmlFilePath = htmlFile.getAbsolutePath();
        // Check if we've already parsed this HTML file
        Set<String> coveredMethods = parsedHtmlCache.get(htmlFilePath);
        if (coveredMethods == null) {
            // Parse the HTML file and extract all covered methods
            coveredMethods = ConcurrentHashMap.newKeySet();
            Document doc = Jsoup.parse(htmlFile);
            Elements spans = doc.select("span[id^=L]");
            /* We parse the HTML file using Jsoup. The way to identify covered method is to check the span class.
             * It is either "fc" (fully covered) or "fc bfc" (partially covered), or "nc" (not covered).
             * Then, we also have to consider <init> (constructors) and <clinit> (static initializers). They won't appear
             * with <init> or <clinit> in the class html file. */
            for (Element span : spans) {
                String codeLine = span.text();
                String clazz = span.className();
                // Only process covered lines
                if (clazz.contains("fc")) {
                    // Store the entire covered line for later pattern matching
                    coveredMethods.add(codeLine);
                }
            }
            // Cache the parsed results
            parsedHtmlCache.put(htmlFilePath, coveredMethods);
            log.debug("Cached {} covered method calls from {}", coveredMethods.size(), htmlFilePath);
        }
        // Now check if our specific third-party method is in the covered set
        String className = thirdPartyMethod.substring(0, thirdPartyMethod.lastIndexOf('.'));
        String shortClassName = className.substring(className.lastIndexOf('.') + 1);
        String methodName = thirdPartyMethod.substring(thirdPartyMethod.lastIndexOf('.') + 1);
        // Check for exact match or pattern match
        for (String codeLine : coveredMethods) {
            // For Constructor <init>
            if ("<init>".equals(methodName)) {
                if (codeLine.contains("new " + shortClassName + "(")) {
                    return true;
                }
            }
            // For Static initializer <clinit>
            else if ("<clinit>".equals(methodName)) {
                // Look for any covered line mentioning the class name
                if (codeLine.contains(shortClassName)) {
                    return true;
                }
            }
            // For Normal method
            else {
                if (codeLine.contains(methodName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
