# API-Finder

The API-Finder is the core analysis component of Fika that identifies uncovered third-party API calls and generates detailed information about how to reach them from public entry points. It performs static analysis using call graph construction, coverage analysis, and source code extraction to provide comprehensive data for test generation.

## Overview

The API-Finder takes a compiled JAR file, analyzes its bytecode to build a call graph, identifies all third-party method invocations, filters out already-covered methods using JaCoCo reports, finds execution paths from public methods to uncovered third-party calls, and extracts complete source code with contextual information for test generation.

The problem API-finder tries to address is the difficulty of automatically identifying which third-party API calls are not adequately tested and determining the simplest execution paths to trigger those calls. This is particularly complex when dealing with:
- Deep call chains spanning multiple methods
- Method overloading requiring precise signature matching
- Constructors and static initializers
- Multiple paths to the same target method
- Differences between Bytecode and Source code representations

## Architecture

The API-Finder follows this workflow:

1. **Initialization**: Load the project JAR and build a comprehensive call graph
2. **Entry Point Detection**: Identify all public methods as potential test entry points
3. **Third-Party Discovery**: Find all third-party method calls in the codebase
4. **Coverage Filtering**: Filter out already-covered third-party calls using JaCoCo reports
5. **Path Finding**: Compute shortest paths from entry points to uncovered third-party methods
6. **Source Extraction**: Extract complete source code for all methods along each path
7. **Context Gathering**: Collect constructors, setters, and imports
8. **Complexity Analysis**: Calculate condition counts and call counts for prioritization
9. **Output Generation**: Write comprehensive JSON reports for test generation

## How It Works

### 1. Third-Party Method Identification

**Why**: Determining which methods belong to third-party libraries versus project code is non-trivial, especially when dealing with transitive dependencies and package naming conflicts.

**Implementation**: The discovery process uses multiple strategies:

- **Package Mapping**: Uses the preprocessor-generated JSON file that maps package names to Maven coordinates. This allows Fika to identify third-party packages quickly.
  
- **Ignored Prefixes**: Maintains a list of prefixes to exclude (e.g., `java.`, `jdk.`, `sun.`, `com.sun.`) plus the project's own package name. Any additional package names that should be ignored are loaded from [ignored_packages.txt](src/main/resources/ignored_packages.txt).

- **Call Graph Analysis**: Iterates through the entire call graph to find all call edges where the target method belongs to a third-party package. Each (caller, callee) pair is recorded.

The implementation is in `MethodExtractor.findAllThirdPartyMethodPairs()`:
- First pass: Registers all third-party calls to detect when a single class makes multiple calls to the same third-party method (needed for precise coverage analysis)
- Second pass: Identifies all uncovered third-party method pairs after filtering

### 2. Entry Point Detection

**Why**: Identifying which methods should be considered as potential test entry points affects the required complexity from the generated tests.

**Implementation**: Fika identifies all **public methods** in the project's package as entry points (`MethodExtractor.detectEntryPoints()`).

The entry point detection uses SootUp's class hierarchy analysis to:
1. Filter classes by project package name
2. Extract all public methods from those classes
3. Use these as the starting points for path finding

### 3. Coverage Measurement and JaCoCo Report Parsing

**Why**: Accurately determining whether a specific third-party method call is already covered by existing tests is complex. The same third-party method might be called from multiple locations in a class, and we need to know if the *specific* call site is covered, not just if the method has been executed somewhere.

**Implementation**: The coverage filtering (`CoverageFilter`) uses a two-level caching strategy with both HTML and XML report parsing:

#### Simple Coverage Check (Single Call Site)
When a class has only one call site to a particular third-party method:
- Parse the JaCoCo HTML report for the class
- Look for lines containing the third-party method call
- If any such line is marked as covered (green), the call is considered covered

#### Precise Coverage Check (Multiple Call Sites)
When a class calls the same third-party method multiple times, we need to know which specific call site is covered:

1. **HTML Analysis** (`getTargetCallLines()`):
   - Parse the HTML to find all line numbers where the target method is invoked
   - Handle special cases like constructors (`new ClassName()`) and static initializers
   - Extract line numbers from the `<span id="L123">` elements

2. **XML Analysis** (`getCoveredLinesForMethod()`):
   - Parse the JaCoCo XML report to find covered line numbers for the specific caller method
   - Use method descriptors (JVM signatures) for precise matching, handling overloaded methods
   - Determine method boundaries to isolate lines belonging to the specific method

3. **Intersection Check** (`isPreciseMethodCovered()`):
   - Find the intersection between target call lines (from HTML) and covered lines in the method (from XML)
   - If any intersection exists, the specific call site is covered

**Why it's difficult**: 
- HTML reports show code and coverage but don't provide method-level granularity
- XML reports provide precise line-level coverage per method but don't show actual code
- Method overloading requires matching full JVM descriptors (method name + parameter types)
- Determining method boundaries in XML requires analyzing all method start lines in a class
- Constructor calls look different in bytecode (`<init>`) than in source (`new ClassName()`)
- Static initializers (`<clinit>`) need special handling

**Caching Strategy**: 
Fika uses multiple levels of caching to avoid re-parsing reports:
- `coverageCache`: Caches coverage decisions per (file, method, target) tuple
- `htmlLineCache`: Caches line numbers where targets are called per HTML file
- `xmlCoverageCache`: Caches covered lines per method per XML file
- `targetCallCountCache`: Tracks how many times each class calls each target method

### 4. Path Finding

**Why**: Finding execution paths from public entry points to third-party methods in large codebases can be computationally expensive, and there may be thousands of paths to a single target. We need to find meaningful paths efficiently.

**Implementation**: The path finding algorithm uses a **reverse call graph traversal** combined with forward path reconstruction:

#### Step 1: Build Reverse Call Graph

Creates a map where each method points to all methods that call it. This enables efficient backward traversal from third-party methods to entry points.

#### Step 2: Backward Traversal

- Start from the direct caller of the third-party method (not the third-party method itself, to preserve call site information)
- Use BFS to traverse backward through the reverse call graph
- Skip third-party methods in the path (we want only project methods in the chain)
- Stop when reaching a public entry point
- Return all entry points that can reach the target

#### Step 3: Shortest Path Finding

For each entry point that can reach a target:
- Use forward BFS from the entry point toward the third-party method
- Only traverse through project methods (not other third-party methods)
- Find one of the shortest "direct" path (only the final method in the path is third-party)
- Return the path as a list of method signatures

**Why reverse traversal?** 
- There are typically far fewer third-party method calls than public methods
- Starting from targets and working backward is much more efficient
- It naturally identifies only the paths that actually reach third-party code

**Direct Path Constraint**: 
Paths must be "direct" - only the final method is third-party. This prevents paths like `publicMethod → thirdPartyA → thirdPartyB → targetMethod`, which is out of scope for Fika.

**Path Statistics**: 
Fika also collects statistics about all possible paths (`countPathsAndStats`):
- Total number of paths from entry point to target
- Shortest path length
- Longest path length
These statistics help justify the decision to use the shortest path and can be written to `path-stats.json`.

### 5. Source Code Extraction with Spoon

**Why**: Extracting actual Java source code (not bytecode or Jimple IR) with proper handling of generics, annotations, inner classes, and adding contextual markers to guide test generation.

**Implementation**: Fika uses **Spoon**, a library for Java source code analysis and transformation:

#### Initialization

- Creates a `MavenLauncher` that understands Maven project structure
- Builds a complete AST model of the source code
- Uses no-classpath mode (doesn't require all dependencies to be on classpath)
- Preserves comments for better code readability
- Caches the model to avoid re-parsing for subsequent extractions

#### Method Extraction
Fika handles different method types:

**Regular Methods** (`extractRegularMethod`):
- Finds the method by name and matches parameter types precisely
- Handles method overloading by comparing JVM signatures
- Uses `SpoonMethodFinder.findRegularMethod()` for signature matching

**Constructors** (`extractConstructor`):
- Identifies constructors (represented as `<init>` in bytecode)
- Matches parameter types to handle constructor overloading
- Extracts the complete constructor body

**Static Initializers** (`extractStaticInitializer`):
- Handles `<clinit>` (static initialization blocks)
- Finds all static blocks in the class
- Returns concatenated source of all static initializers

**Why it's difficult**:
- Bytecode method signatures use JVM descriptors (e.g., `(Ljava/lang/String;I)V`) 
- Source code uses different type representations
- Inner classes use `$` in bytecode but `.` in source
- Generic types are erased in bytecode but present in source
- Method overloading requires exact parameter type matching

#### Path Tracking Comments

A unique feature of the source extraction is adding inline comments to indicate the execution path:

For each method in a path, Fika:
1. Identifies the specific call site that leads to the next method in the path
2. Uses `PathCallFinder` to locate the exact invocation in the AST
3. Adds an inline comment after that statement: 
   ```java
   // PATH: Test should invoke the next ClassName.methodName(...) [step in execution path]
   ```

This helps the LLM understand which specific call in the method should be exercised by the test.

**Handling edge cases**:
- If Spoon's comment API fails (due to AST modification restrictions), falls back to string manipulation
- Handles both method calls and constructor invocations (`new ClassName()`)

#### Class Members Extraction

For test generation, Fika also extracts class context:

**Constructors**: All constructors are extracted to help the test instantiate the class.

**Setters**: Methods that:
- Start with "set"
- Have exactly one parameter
- Return void

**Imports**: Fika scans all methods in the path and extracts:
- All import statements from classes involved
- Filters to non-Java standard library imports
- Removes duplicates and sorts alphabetically

### 6. Condition Count Calculation

**Why**: Not all paths are equally easy to test. Paths with many conditional branches (if statements, loops, switches) require more complex test inputs and edge case handling.

**Implementation**: The `RecordCounter` analyzes code complexity by counting control flow conditions:

For each method in the path, Fika counts:
- **If statements** (`CtIf`): Conditional branches
- **For loops** (`CtFor`): Traditional for loops
- **For-each loops** (`CtForEach`): Enhanced for loops
- **While loops** (`CtWhile`): Conditional loops
- **Do-while loops** (`CtDo`): Post-condition loops
- **Switch statements** (`CtSwitch`): Multi-way branches
- **Ternary operators** (`CtConditional`): Inline conditionals (`condition ? true : false`)

Fika uses Spoon's AST to find all control flow elements in each method's body:

**Caching**: Condition counts are cached per method signature to avoid re-parsing.

**Why it matters**: 
The condition count is used to **sort paths** by complexity. When multiple paths reach the same third-party method, Fika prioritizes simpler paths (fewer conditions) because:
- They're easier for LLMs to generate tests for
- Tests are more maintainable and readable
- Less likely to require complex mocking or setup
However, currently Fika generates tests for all identified paths, not just the simplest ones.

### 7. Call Count Calculation

**Why**: Some methods call the same third-party API multiple times. This value is collected only for analysis purposes. Fika does not add duplicate records for multiple calls to the same third-party API when they have the same path.

**Implementation**: Uses Spoon to statically count invocations:

Fika:
1. Parses the caller method's source code
2. Uses `InvocationCounter` visitor to traverse the AST
3. Counts all invocations that match the target method name and class
4. Handles both regular methods and constructors
5. Returns a count

### 8. Test Template Generation

The test template generation is intentionally kept simple. Its only purpose is to provide:
- **Package name**: Where the test class should be located
- **Test class name**: Generated from the path (e.g., `EntryClass_CallerMethod_TargetClass_TargetMethodFikaTest`)
- **Test method name**: Derived from the entry point method name

The template (`Template.java`) contains basic JUnit boilerplate with placeholders:

These placeholders are replaced by actual values. The template is not critical for test generation - modern LLMs can easily generate test structure. It's mainly useful for maintaining consistent naming conventions.

## Output Format

The API-Finder generates a comprehensive JSON report (`third_party_apis_full_methods.json`):

```json
{
  "fullMethodsPaths": [
    {
      "entryPoint": "com.example.MyClass.publicMethod",
      "thirdPartyMethod": "org.library.ThirdParty.targetMethod",
      "path": [
        "com.example.MyClass.publicMethod",
        "com.example.MyClass.helperMethod",
        "org.library.ThirdParty.targetMethod"
      ],
      "methodSources": [
        "public void publicMethod() {\n    helperMethod(); // PATH: Test should invoke...\n}",
        "private void helperMethod() {\n    thirdParty.targetMethod(); // PATH: Test should invoke...\n}"
      ],
      "constructors": ["public MyClass() { ... }"],
      "setters": ["public void setField(String value) { ... }"],
      "imports": ["import org.library.ThirdParty;"],
      "testTemplate": "package com.example;\n\npublic class MyClass_helperMethod_ThirdParty_targetMethodFikaTest {\n    @Test\n    public void testPublicMethod() {\n        // TODO\n    }\n}",
      "conditionCount": 3,
      "callCount": 1
    }
  ]
}
```

Paths are sorted by:
1. **Primary sort**: Condition count (ascending) - simpler paths first
2. **Secondary sort**: Path length (ascending) - shorter paths first

This prioritization ensures that test generation focuses on the most tractable test cases first.

## Key Design Decisions

### 1. Public Methods Only as Entry Points
**Decision**: Only public methods are used as test entry points.

**Rationale**: 
- Main goal of Fika is reachability analysis and public APIs are indicators of intended usage
- Aligns with testing best practices

### 2. Shortest Direct Paths
**Decision**: For each (entry point, target) pair, only one of the shortest direct path is used.

**Rationale**:
- Path explosion: One target may have thousands of paths from different entry points
- Shorter paths are easier to test and understand
- "Direct" means no intermediate third-party calls, ensuring the path is actually executable from project code

### 3. Reverse Call Graph Traversal
**Decision**: Path finding works backward from targets to entry points, then reconstructs paths forward.

**Rationale**:
- Massively more efficient than forward search from thousands of entry points
- Naturally prunes paths that don't reach any third-party code
- Enables quick identification of all relevant entry points

### 4. Two-Phase Coverage Checking
**Decision**: Simple HTML check for single call sites, precise HTML+XML check for multiple call sites.

**Rationale**:
- Performance: Parsing XML is expensive; avoid it when not needed
- Accuracy: When necessary, combine HTML (for code context) with XML (for precise coverage)
- Caching: Both approaches benefit from multi-level caching

### 5. Source Code Over Bytecode
**Decision**: Extract actual Java source code using Spoon rather than Jimple IR from Soot.

**Rationale**:
- LLMs are trained on source code, not IR
- Source preserves variable names, comments, and idioms
- Generics and annotations are lost in bytecode
- More readable for human verification

## Stack

- **SootUp**: Call graph construction and bytecode analysis
- **Spoon**: Source code parsing, analysis, and transformation  
- **JaCoCo Reports**: Coverage information (HTML and XML)
- **Jsoup**: HTML parsing for JaCoCo reports
- **Jackson/Gson**: JSON serialization
- **SLF4J**: Logging
- **PicoCLI**: Command-line interface

## Usage

See the main [README](../README.md) for usage instructions.

## Performance Considerations

- **Model Caching**: Spoon models are cached and reused for all methods in a project
- **Multi-level Coverage Caching**: Coverage decisions, HTML line numbers, and XML coverage data are all cached
- **Condition Caching**: Method condition counts are cached to avoid re-parsing
- **Lazy Parsing**: XML reports are only parsed when precise coverage checks are needed

For large projects, the most expensive operations are:
1. Building the Spoon model (one-time cost per project)
2. Building the call graph (one-time cost)
3. Path finding (mitigated by reverse traversal)
4. XML parsing for coverage (mitigated by caching and selective use)

## Limitations

- **Static Analysis**: Cannot handle reflection-based method calls or lambda expressions
- **Source Code Required**: For best results, source code must be available
- **JaCoCo Reports Required**: Coverage filtering requires JaCoCo HTML+XML reports
- **Public Methods Only**: Private/protected methods are not considered as entry points
- **Direct Paths Only**: Paths with intermediate third-party calls are excluded

## Future Improvements

- Integration with other coverage tools beyond JaCoCo (When Fika considers the quality of test oracles)
- Parallel processing for large projects
- Incremental analysis (only analyze changed code)
