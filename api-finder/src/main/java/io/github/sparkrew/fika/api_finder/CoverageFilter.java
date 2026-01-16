package io.github.sparkrew.fika.api_finder;

import io.github.sparkrew.fika.api_finder.utils.CoverageLogger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.NodeList;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.Type;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * CoverageFilter checks if methods are already covered by tests using JaCoCo reports.
 * It uses both HTML and XML reports to determine coverage status.
 * Caches results for performance optimization.
 * ToDo: This class can be optimized in many ways. One easy way is to record the line number where the target method is
 *       called when we extract the method sources, and then directly check with the XML file, instead of trying to
 *       parse the HTML. Then we could also remove all the complicated logic to handle superclasses, etc. The accuracy
 *       will also be improved.
 */
public class CoverageFilter {

    private static final Logger log = LoggerFactory.getLogger(CoverageFilter.class);
    // Cache - Map<htmlFilePath, Map<thirdPartyMethod, isCovered>>
    private static final Map<String, Map<String, Boolean>> coverageCache = new ConcurrentHashMap<>();
    // Cache for parsed HTML documents: Map<htmlFilePath, Map<targetMethod, Set<lineNumbers>>>
    private static final Map<String, Map<String, Set<Integer>>> htmlLineCache = new ConcurrentHashMap<>();
    // Cache for XML method coverage: Map<xmlFilePath, Map<methodSig, Set<coveredLineNumbers>>>
    private static final Map<String, Map<String, Set<Integer>>> xmlCoverageCache = new ConcurrentHashMap<>();
    // Cache to track if a class has multiple calls to same target: Map<className, Map<targetMethod, count>>
    private static final Map<String, Map<String, Integer>> targetCallCountCache = new ConcurrentHashMap<>();

    /**
     * Clears all caches.
     */
    public static void clearCache() {
        coverageCache.clear();
        htmlLineCache.clear();
        xmlCoverageCache.clear();
        targetCallCountCache.clear();
        log.debug("All coverage caches cleared");
    }

    /**
     * Checks if a given method is covered by tests using JaCoCo reports.
     * Uses HTML to find line numbers where target is called, then uses XML to check
     * if those specific lines are covered in the given method.
     *
     * @param method         The method signature to check for coverage
     * @param target         The target third-party method signature
     * @param jacocoHtmlDirs List of JaCoCo HTML report directories (site/jacoco roots)
     * @return true if the method is covered by tests, false otherwise
     */
    public static boolean isAlreadyCoveredByTests(MethodSignature method, MethodSignature target,
                                                  List<File> jacocoHtmlDirs, boolean enableAnalysisLogs) {
        try {
            String fullClassName = method.getDeclClassType().getFullyQualifiedName();
            String simpleClassName = fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
            String packageName = extractPackageName(fullClassName);
            String thirdPartyMethod = target.getDeclClassType().getFullyQualifiedName() + "."
                    + target.getName();
            String thirdPartyMethodFull = target.getDeclClassType().getFullyQualifiedName()
                    + "." + target.getName()
                    + "(" + target.getParameterTypes().stream()
                    .map(Type::toString)
                    .collect(Collectors.joining(", ")) + ")";
            // Check if this class has multiple calls to the same target
            boolean needsPreciseCheck = hasMultipleTargetCalls(fullClassName, thirdPartyMethod);
            for (File dir : jacocoHtmlDirs) {
                File htmlFile = dir.toPath()
                        .resolve(packageName)
                        .resolve(simpleClassName + ".java.html")
                        .toFile();

                if (!htmlFile.exists()) {
                    continue;
                }
                String htmlFilePath = htmlFile.getAbsolutePath();
                String cacheKey = htmlFilePath + "|" + method.getName() + "|" + thirdPartyMethod;
                // Check cache first
                Map<String, Boolean> fileCache = coverageCache.get(htmlFilePath);
                if (fileCache != null && fileCache.containsKey(cacheKey)) {
                    log.debug("Cache hit for {} in method {}", thirdPartyMethod, method.getName());
                    return fileCache.get(cacheKey);
                }
                boolean isCovered;
                // We do this check because if we only use the HTML files, when there are multiple calls to the same
                // third party method within one class, we cannot determine whether the actual target we are looking
                // for is covered.
                if (needsPreciseCheck) {
                    log.debug("Multiple calls detected, using precise XML checking for {} in method {} in class {}",
                            thirdPartyMethod, method.getName(), method.getDeclClassType().getFullyQualifiedName());
                    isCovered = isPreciseMethodCovered(htmlFile, dir, method, target, thirdPartyMethod);
                } else {
                    // Quick HTML check is sufficient (only one call in entire class)
                    isCovered = isMethodCoveredInClass(htmlFile, method, target, thirdPartyMethod);
                }
                coverageCache.computeIfAbsent(htmlFilePath, k -> new ConcurrentHashMap<>())
                        .put(cacheKey, isCovered);
                if (isCovered) {
                    String callerSignature = method.getDeclClassType().getFullyQualifiedName() + "." + method.getName() +
                            "(" + method.getParameterTypes().stream().map(Type::toString)
                            .collect(Collectors.joining(", ")) + ")";
                    if (enableAnalysisLogs) {
                        CoverageLogger.logCoverage(callerSignature, thirdPartyMethodFull, true);
                    }
                    return true;
                }
            }
            String callerSignature = method.getDeclClassType().getFullyQualifiedName() + "." + method.getName() +
                    "(" + method.getParameterTypes().stream().map(Type::toString)
                    .collect(Collectors.joining(", ")) + ")";
            if (enableAnalysisLogs) {
                CoverageLogger.logCoverage(callerSignature, thirdPartyMethodFull, false);
            }
            return false;
        } catch (Exception e) {
            log.error("Error checking coverage for method: {}", method.getName(), e);
            return false;
        }
    }

    /**
     * Check if a class has multiple calls to the same target method.
     * This helps determine if we need precise XML checking.
     */
    private static boolean hasMultipleTargetCalls(String fullClassName, String thirdPartyMethod) {
        Map<String, Integer> classTargets = targetCallCountCache.get(fullClassName);
        if (classTargets != null) {
            Integer count = classTargets.get(thirdPartyMethod);
            return count != null && count > 1;
        }
        return false;
    }

    /**
     * Register that a class calls a specific target method.
     * This is called from the MethodExtractor when the methods are processed first. So we don't have to do it again.
     * This allows the filter to use quick HTML checks when there's only one call,
     * and precise XML checks when there are multiple calls to the same target in a class.
     */
    public static void registerTargetCall(String fullClassName, String thirdPartyMethod) {
        targetCallCountCache.computeIfAbsent(fullClassName, k -> new ConcurrentHashMap<>())
                .merge(thirdPartyMethod, 1, Integer::sum);
    }

    /**
     * Performs precise coverage checking using HTML + XML reports.
     * Gets line numbers from HTML where the target method is called and gets covered line numbers from XML for
     * the specific method. Then, checks if there's any intersection.
     * If method not found in current class, recursively checks superclass.
     */
    private static boolean isPreciseMethodCovered(File htmlFile, File jacocoDir,
                                                  MethodSignature method, MethodSignature target,
                                                  String thirdPartyMethod) throws Exception {
        String fullClassName = method.getDeclClassType().getFullyQualifiedName();

        // Get line numbers where target is called (from HTML)
        Set<Integer> targetCallLines = getTargetCallLines(htmlFile, method, target);
        if (targetCallLines.isEmpty()) {
            log.warn("No lines found calling target {} in class {}", thirdPartyMethod, fullClassName);
            // We had the function to check the superclasses as the method might be inherited, but removed it.
            // If needed again check the commit "Remove superclass check" on 16.01.2026.
            return false;
        }
        log.debug("Target {} called on lines: {} in class {}", thirdPartyMethod, targetCallLines, fullClassName);
        // Find and parse XML report. The XML file does not have actual code lines, only line numbers. That's why we
        // need to cross-reference with HTML.
        File xmlFile = findXmlReport(jacocoDir);
        if (xmlFile == null || !xmlFile.exists()) {
            log.warn("XML report not found in {}, cannot perform precise check", jacocoDir);
            return false;
        }
        
        String methodName = method.getName();
        String methodDesc = buildMethodDescriptor(method);
        // Get covered lines for our specific method (from XML)
        Set<Integer> coveredLinesInMethod = getCoveredLinesForMethod(xmlFile, fullClassName, methodName, methodDesc);
        if (coveredLinesInMethod.isEmpty()) {
            log.debug("No covered lines found for method {} in class {}", method.getName(), fullClassName);
            return false;
        }
        log.debug("Method {} has covered lines: {} in class {}", method.getName(), coveredLinesInMethod, fullClassName);
        // Check intersection - is the target called on any covered line in this method?
        Set<Integer> intersection = new HashSet<>(targetCallLines);
        intersection.retainAll(coveredLinesInMethod);
        if (!intersection.isEmpty()) {
            log.debug("Target {} is covered in method {} on lines: {}",
                    thirdPartyMethod, method.getName(), intersection);
            return true;
        }
        log.debug("No intersection between target call lines and covered lines in method {}", method.getName());
        return false;
    }

    /**
     * Checks if a code line represents a constructor declaration.
     * Constructor declarations look like: "public ClassName(" or "ClassName(" etc.
     * This is used to detect implicit super() calls in child class constructors.
     */
    private static boolean isConstructorDeclaration(String codeLine, String className) {
        // Constructor patterns:
        // - public ClassName(
        // - protected ClassName(
        // - private ClassName(
        // - ClassName( (package-private)
        // Match word boundary before className to avoid partial matches
        String[] modifiers = {"public ", "protected ", "private ", ""};
        for (String modifier : modifiers) {
            String pattern = modifier + className + "(";
            if (codeLine.contains(pattern)) {
                // Make sure it's not a "new ClassName(" call
                if (!codeLine.contains("new " + className + "(")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if the caller class extends the target class by checking the HTML source.
     * Used to determine if we should look for super() calls instead of new ClassName() calls.
     */
    private static boolean callerExtendsTarget(File htmlFile, MethodSignature target) {
        try {
            Document doc = Jsoup.parse(htmlFile);
            String targetClassName = target.getDeclClassType().getFullyQualifiedName();
            String shortTargetClassName = targetClassName.substring(targetClassName.lastIndexOf('.') + 1);
            // Look for class declaration with extends keyword
            Element pre = doc.selectFirst("pre");
            if (pre != null) {
                String source = pre.wholeText();
                if (source.contains("class ") && source.contains("extends " + shortTargetClassName)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Error checking class inheritance: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Extracts line numbers from HTML where the target method is called.
     * This parses the HTML to find which line numbers contain calls to the target.
     * For constructors, if the caller extends the target, looks for super() calls OR implicit constructor calls.
     * For regular methods, also checks for super.methodName() calls (overridden methods).
     */
    private static Set<Integer> getTargetCallLines(File htmlFile, MethodSignature caller, MethodSignature target) throws Exception {
        String htmlFilePath = htmlFile.getAbsolutePath();
        String targetKey = target.getDeclClassType().getFullyQualifiedName() + "." + target.getName();
        Map<String, Set<Integer>> fileCache = htmlLineCache.get(htmlFilePath);
        if (fileCache != null && fileCache.containsKey(targetKey)) {
            return fileCache.get(targetKey);
        }
        Set<Integer> lineNumbers = new HashSet<>();
        Document doc = Jsoup.parse(htmlFile);
        String targetClassName = target.getDeclClassType().getFullyQualifiedName();
        String shortClassName = targetClassName.substring(targetClassName.lastIndexOf('.') + 1);
        String methodName = target.getName();
        // Check if this is a child-to-parent constructor call case
        boolean isChildConstructor = "<init>".equals(methodName) && callerExtendsTarget(htmlFile, target);
        // Get the caller's simple class name for implicit constructor detection
        String callerClassName = caller.getDeclClassType().getFullyQualifiedName();
        String callerSimpleClassName = callerClassName.substring(callerClassName.lastIndexOf('.') + 1);
        Elements spans = doc.select("span[id^=L]");
        for (Element span : spans) {
            String lineId = span.attr("id");
            String codeLine = span.text();
            boolean containsTarget = false;
            if ("<init>".equals(methodName)) {
                if (isChildConstructor) {
                    // Child class calling parent constructor - look for:
                    // 1. Explicit super() call
                    // 2. Implicit call via child constructor (e.g., "public ChildClass() {")
                    if (codeLine.contains("super(")) {
                        containsTarget = true;
                        log.debug("Found explicit super() call in line: {}", codeLine);
                    } else if (isConstructorDeclaration(codeLine, callerSimpleClassName)) {
                        // This is the child constructor declaration - implicit super() call
                        containsTarget = true;
                        log.debug("Found implicit constructor call (child constructor declaration) in line: {}", codeLine);
                    }
                } else {
                    // Regular constructor call
                    containsTarget = codeLine.contains("new " + shortClassName + "(");
                }
            } else if ("<clinit>".equals(methodName)) {
                containsTarget = codeLine.contains(shortClassName);
            } else {
                // Regular method - check for direct call or super.methodName() call
                if (codeLine.contains(methodName + "(")) {
                    containsTarget = true;
                }
            }
            if (containsTarget) {
                // Extract line number from id. ID is in the format "L123". We have to parse it to get 123.
                try {
                    int lineNum = Integer.parseInt(lineId.substring(1));
                    lineNumbers.add(lineNum);
                } catch (NumberFormatException e) {
                    log.warn("Could not parse line number from id: {}", lineId);
                }
            }
        }
        htmlLineCache.computeIfAbsent(htmlFilePath, k -> new ConcurrentHashMap<>())
                .put(targetKey, lineNumbers);
        return lineNumbers;
    }

    /**
     * Parses JaCoCo XML report to extract covered line numbers for a specific method.
     * Only checks the specified class, does not recurse into superclasses.
     */
    private static Set<Integer> getCoveredLinesForMethod(File xmlFile, String fullClassName,
                                                                String methodName, String methodDesc) throws Exception {
        String cacheKey = xmlFile.getAbsolutePath();
        String methodKey = fullClassName + "." + methodName + methodDesc;
        Map<String, Set<Integer>> fileCache = xmlCoverageCache.get(cacheKey);
        if (fileCache != null && fileCache.containsKey(methodKey)) {
            return fileCache.get(methodKey);
        }
        // We remove DTD validation to avoid errors.
        Set<Integer> coveredLines = new HashSet<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        org.w3c.dom.Document doc = builder.parse(xmlFile);
        // Find the package that contains our class
        String xmlClassName = fullClassName.replace('.', '/');
        NodeList packages = doc.getElementsByTagName("package");
        for (int i = 0; i < packages.getLength(); i++) {
            org.w3c.dom.Element pkg = (org.w3c.dom.Element) packages.item(i);
            NodeList classes = pkg.getElementsByTagName("class");
            org.w3c.dom.Element targetClass = null;
            String sourceFileName = null;
            for (int j = 0; j < classes.getLength(); j++) {
                org.w3c.dom.Element clazz = (org.w3c.dom.Element) classes.item(j);
                String className = clazz.getAttribute("name");
                if (className.equals(xmlClassName)) {
                    targetClass = clazz;
                    sourceFileName = clazz.getAttribute("sourcefilename");
                    break;
                }
            }
            if (targetClass == null) {
                continue;
            }
            Integer methodStartLine = null;
            Integer methodEndLine = null;
            NodeList methods = targetClass.getElementsByTagName("method");
            List<Integer> allMethodStarts = new ArrayList<>();
            for (int k = 0; k < methods.getLength(); k++) {
                org.w3c.dom.Element methodElem = (org.w3c.dom.Element) methods.item(k);
                String lineAttr = methodElem.getAttribute("line");
                if (!lineAttr.isEmpty()) {
                    allMethodStarts.add(Integer.parseInt(lineAttr));
                }
            }
            // Sort to find method boundaries
            Collections.sort(allMethodStarts);
            for (int k = 0; k < methods.getLength(); k++) {
                org.w3c.dom.Element methodElem = (org.w3c.dom.Element) methods.item(k);
                String xmlMethodName = methodElem.getAttribute("name");
                String xmlMethodDesc = methodElem.getAttribute("desc");
                if (xmlMethodName.equals(methodName) && xmlMethodDesc.equals(methodDesc)) {
                    String lineAttr = methodElem.getAttribute("line");
                    if (!lineAttr.isEmpty()) {
                        methodStartLine = Integer.parseInt(lineAttr);
                        // Find the next method start line that is greater than current start line
                        // This handles cases where multiple methods (e.g., overloaded constructors) 
                        // share the same line number in the XML
                        for (int nextLine : allMethodStarts) {
                            if (nextLine > methodStartLine) {
                                methodEndLine = nextLine - 1;
                                break;
                            }
                        }
                    }
                    break;
                }
            }
            if (methodStartLine == null) {
                log.warn("Could not find start line for method {}", methodKey);
                continue;
            }
            // Now we find the matching sourcefile element. Source element is a sibling to class elements.
            NodeList sourcefiles = pkg.getElementsByTagName("sourcefile");
            org.w3c.dom.Element targetSourceFile = null;
            for (int j = 0; j < sourcefiles.getLength(); j++) {
                org.w3c.dom.Element sourcefile = (org.w3c.dom.Element) sourcefiles.item(j);
                String sfName = sourcefile.getAttribute("name");

                if (sfName.equals(sourceFileName)) {
                    targetSourceFile = sourcefile;
                    break;
                }
            }
            if (targetSourceFile == null) {
                log.warn("Could not find sourcefile {} for class {}", sourceFileName, fullClassName);
                continue;
            }
            // Parse the sourcefile for covered lines within method boundaries
            NodeList lines = targetSourceFile.getElementsByTagName("line");
            for (int l = 0; l < lines.getLength(); l++) {
                org.w3c.dom.Element line = (org.w3c.dom.Element) lines.item(l);
                int lineNum = Integer.parseInt(line.getAttribute("nr"));
                int coveredInstructions = Integer.parseInt(line.getAttribute("ci"));
                // Check if line is covered and within method bounds
                if (coveredInstructions > 0) {
                    if (methodEndLine != null) {
                        if (lineNum >= methodStartLine && lineNum <= methodEndLine) {
                            coveredLines.add(lineNum);
                        }
                    } else {
                        // No end line found, include all lines after start
                        // This happens for the last method in the class
                        if (lineNum >= methodStartLine) {
                            coveredLines.add(lineNum);
                        }
                    }
                }
            }
            break;
        }
        xmlCoverageCache.computeIfAbsent(cacheKey, k -> new ConcurrentHashMap<>())
                .put(methodKey, coveredLines);
        
        log.debug("Found {} covered lines for method {} in class {}",
                coveredLines.size(), methodName, fullClassName);
        return coveredLines;
    }

    /**
     * Builds a JVM method descriptor from a MethodSignature.
     * Example: (Ljava/lang/String;I)V
     */
    private static String buildMethodDescriptor(MethodSignature method) {
        StringBuilder desc = new StringBuilder("(");

        for (Type paramType : method.getParameterTypes()) {
            desc.append(typeToDescriptor(paramType));
        }
        desc.append(")");
        desc.append(typeToDescriptor(method.getType()));
        return desc.toString();
    }

    /**
     * Converts a Type to JVM type descriptor format.
     */
    private static String typeToDescriptor(Type type) {
        String typeName = type.toString();
        int arrayDimensions = 0;
        String baseTypeName = typeName;
        while (baseTypeName.endsWith("[]")) {
            arrayDimensions++;
            baseTypeName = baseTypeName.substring(0, baseTypeName.length() - 2);
        }
        StringBuilder result = new StringBuilder();
        result.append("[".repeat(Math.max(0, arrayDimensions)));
        switch (baseTypeName) {
            case "byte" -> {
                result.append("B");
                return result.toString();
            }
            case "char" -> {
                result.append("C");
                return result.toString();
            }
            case "double" -> {
                result.append("D");
                return result.toString();
            }
            case "float" -> {
                result.append("F");
                return result.toString();
            }
            case "int" -> {
                result.append("I");
                return result.toString();
            }
            case "long" -> {
                result.append("J");
                return result.toString();
            }
            case "short" -> {
                result.append("S");
                return result.toString();
            }
            case "boolean" -> {
                result.append("Z");
                return result.toString();
            }
            case "void" -> {
                result.append("V");
                return result.toString();
            }
        }
        result.append("L").append(baseTypeName.replace('.', '/')).append(";");
        return result.toString();
    }

    /**
     * Finds the JaCoCo XML report file in the given directory.
     */
    private static File findXmlReport(File jacocoDir) {
        // Unlike HTML, the XML report has a fixed name: jacoco.xml and there is only one in the root folder.
        File xmlFile = new File(jacocoDir, "jacoco.xml");
        if (xmlFile.exists()) {
            return xmlFile;
        }
        log.warn("XML report not found in: " + jacocoDir.getAbsolutePath());
        return null;
    }

    private static String extractPackageName(String fullClassName) {
        String[] parts = fullClassName.split("\\.");
        // Find the first part that starts with a capital letter (the outermost class)
        int outermostClassIdx = -1;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] != null && !parts[i].isEmpty() && Character.isUpperCase(parts[i].charAt(0))) {
                outermostClassIdx = i;
                break;
            }
        }
        if (outermostClassIdx == -1) {
            log.warn("Could not find class name in: {}", fullClassName);
            return "";
        }
        // Everything before the outermost class is the package
        return outermostClassIdx > 0
                ? String.join(".", Arrays.copyOfRange(parts, 0, outermostClassIdx))
                : "";
    }

    /**
     * Quick check: is the target method covered anywhere in the class?
     * Used when there's only one call to the target in the entire class.
     * For constructors, if the caller extends the target, looks for super() calls OR implicit constructor calls.
     * For regular methods, also checks for super.methodName() calls.
     * If not found in current class, checks superclass.
     */
    private static boolean isMethodCoveredInClass(File htmlFile, MethodSignature caller,
                                                  MethodSignature target, String thirdPartyMethod) throws Exception {
        String className = thirdPartyMethod.substring(0, thirdPartyMethod.lastIndexOf('.'));
        String shortClassName = className.substring(className.lastIndexOf('.') + 1);
        String methodName = thirdPartyMethod.substring(thirdPartyMethod.lastIndexOf('.') + 1);
        // Check if this is a child-to-parent constructor call case
        boolean isChildConstructor = "<init>".equals(methodName) && callerExtendsTarget(htmlFile, target);
        // Get the caller's simple class name for implicit constructor detection
        String callerClassName = caller.getDeclClassType().getFullyQualifiedName();
        String callerSimpleClassName = callerClassName.substring(callerClassName.lastIndexOf('.') + 1);
        Document doc = Jsoup.parse(htmlFile);
        Elements spans = doc.select("span[id^=L]");
        for (Element span : spans) {
            String codeLine = span.text();
            String clazz = span.className();
            // Only process covered lines. fc means fully covered.
            if (clazz.contains("fc")) {
                if ("<init>".equals(methodName)) {
                    if (isChildConstructor) {
                        // Child class calling parent constructor - look for:
                        // 1. Explicit super() call
                        // 2. Implicit call via child constructor declaration
                        if (codeLine.contains("super(")) {
                            log.debug("Found covered super() call in quick check");
                            return true;
                        }
                        if (isConstructorDeclaration(codeLine, callerSimpleClassName)) {
                            log.debug("Found covered implicit constructor call (child constructor) in quick check");
                            return true;
                        }
                    } else {
                        if (codeLine.contains("new " + shortClassName + "(")) {
                            return true;
                        }
                    }
                } else if ("<clinit>".equals(methodName)) {
                    if (codeLine.contains(shortClassName)) {
                        return true;
                    }
                } else {
                    // Regular method - check for direct call or super.methodName() call
                    if (codeLine.contains(methodName + "(")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
