# Pure-LLM Reachability Scenario Generation Pipeline

This document describes the **pure-LLM** pipeline for generating JUnit 5 reachability scenarios. Unlike the original pipeline (which reads pre-extracted method data from a JSON file and feeds it to the LLM), this approach gives the LLM **all the project source code** and asks it to identify and generate scenarios for third-party dependency call sites on its own.

## Overview

| Aspect | Original pipeline | Pure-LLM pipeline |
|---|---|---|
| LLM input | Structured JSON (entryPoint, methodSources, constructors, etc.) | All `.java` source files from the project |
| LLM task | Generate one scenario for one specific call path | Identify all dependency call sites and generate scenarios for each |
| JSON file role | Drives prompting (tells the LLM what to test) | Validation only (used to measure how many targets the LLM covered) |
| Feedback loop | Compilation errors + coverage gaps | Compilation/runtime errors only |

## Pipeline Flow

```
┌─────────────┐
│  1. Extract  │  Walk project, read all .java files (excl. src/test/)
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  2. Generate │  Prompt LLM with all source code
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  3. Write    │  Write generated test classes to src/test/java/
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  4. Run      │  Maven compile + test + JaCoCo coverage
└──────┬──────┘
       │
       ▼
┌─────────────┐     ┌───────────────┐
│  5. Decide   │────▶│  6. Validate  │  (when tests pass or max iterations)
└──────┬──────┘     └──────┬────────┘
       │ (retry on               │
       │  compile error)         ▼
       │               ┌────────────────┐
       └──────◀────────│  7. Finalize   │  Report: X/N targets covered
                       └────────────────┘
```

### Step 1: Extract

Walks the project directory and reads every `.java` file, **excluding** anything under `src/test/`. Each file's content is prefixed with a `// FILE: <relative-path>` header. The result is a single string containing the entire project source.

**Module:** `extract_java_sources.py`

### Step 2: Generate

On the **first iteration**, the LLM receives the full project source code and is asked to:

1. Analyze the code
2. Identify all third-party / dependency method call sites
3. Generate a separate JUnit 5 test class for each call site

On **retry iterations** (after compilation errors), the LLM receives:
- The compilation/runtime errors from Maven
- The previously generated code
- The full project source code (for reference)

The LLM is asked to fix the errors while preserving all hard constraints.

**Module:** `pure_llm_graph.py` → `node_generate`

### Step 3: Write

Each generated test class is written to `src/test/java/<package>/<ClassName>.java` under the project root. Test classes are split from the LLM output using the `// --- NEW TEST CLASS ---` separator.

### Step 4: Run

Runs `mvn test` with JaCoCo coverage enabled. If `--run-all` is not set, only the generated test classes are executed.

### Step 5: Decide

- **Tests passed →** proceed to validation
- **Tests failed + iterations remaining →** retry (go back to step 2 with error feedback)
- **Max iterations reached →** proceed to validation anyway

**Only compilation/runtime errors are fed back to the LLM.** Coverage information is NOT used as feedback.

### Step 6: Validate

Loads the JSON targets file and checks each `(thirdPartyMethod, directCaller)` pair against JaCoCo coverage data. Reports:

- Total call sites in the JSON: **N**
- Covered by the generated tests: **X**
- Coverage rate: **X/N**

Each target is checked using the existing `get_coverage_result()` function which parses JaCoCo XML reports.

### Step 7: Finalize

Prints the coverage summary. If the tests did not compile/pass, cleans up the generated test files.

## Usage

```bash
python -m junit_agent.main_pure_llm /path/to/java/project \
    --targets targets.json \
    --api deepseek \
    --model deepseek-chat \
    --iters 5 \
    --test-package generated \
    --output-json results.json \
    --log-file run.log
```

### Arguments

| Argument | Required | Description |
|---|---|---|
| `repo_root` | Yes | Path to the Maven Java project |
| `--targets` | Yes | JSON file with third-party method targets (for validation) |
| `--test-package` | No | Package for generated tests (default: `generated`) |
| `--api` | No | `hf` (local HuggingFace) or `deepseek` (default: `hf`) |
| `--model` | No | Model identifier |
| `--api-key` | No | API key for DeepSeek |
| `--iters` | No | Max retry iterations (default: 5) |
| `--temp` | No | Sampling temperature (default: 0.1) |
| `--max-new` | No | Max new tokens (default: 4096) |
| `--run-all` | No | Run full Maven test suite instead of only generated tests |
| `--output-json` | No | Save results summary to JSON |
| `--log-file` | No | Append detailed logs to file |

## Prompt Templates

The prompt templates are defined in `pipeline/src/junit_agent/prompts.py`.

### System Prompt

```
You are an expert Java developer who writes good unit tests in order to maximize
code coverage. You are specialized in Maven and JUnit 5 testing framework. You are
an expert at identifying third-party and dependency method calls in Java source code.
```

### First-iteration User Prompt

```
You are given the complete source code of a Java project. Your task is to generate
JUnit 5 reachability tests that exercise as many third-party / dependency method call
sites as possible.

A "reachability scenario" is a piece of code whose only goal is to invoke a specific third-party
method during execution. No assertions, inspections, or verifications are required.

A "third-party / dependency method" is any method that belongs to an external library
(not defined in this project's own source code). Examples include calls to Apache
Commons, Google Guava, Jackson, SLF4J, database drivers, HTTP clients, etc.

Instructions:
1. Analyze the provided source code.
2. Identify all call sites where a third-party / dependency method is invoked.
3. For each such call site, generate a separate JUnit 5 test class that triggers
   execution of that call site.
4. Each test class should have exactly ONE @Test method.
5. Name each test class descriptively:
   Reachability_<ClassName>_<MethodName>_<index>Test

You are provided with:
projectSourceCode: The complete source code of the Java project (all .java files).

projectSourceCode:
{projectSourceCode}

Hard constraints:
- Each test class MUST NOT extend any other class (no 'extends' keyword).
- Each test class MUST NOT override any methods (no @Override annotations).
- Do NOT create anonymous inner classes.
- Use real objects whenever possible.
- Use mocks only when necessary, and ONLY for objects required as constructor
  parameters, provided those objects are not directly related to the target method call.
- Do NOT mock or spy on the class under test.
- Do NOT alter, stub, or control the behavior of ANY method in the call path.
- Use only: junit-jupiter, mockito-core, and mockito-junit-jupiter.
- Do NOT add any assertions, verifications, or inspections.
- package declaration MUST be: {test_package}
- exactly ONE @Test method per test class
- Return ONLY the complete Java source code for ALL test classes.
- Separate each test class with a line: // --- NEW TEST CLASS ---
- Do NOT include explanations, markdown, or extra text.
```

### Retry Prompt (compilation errors)

```
The previously generated tests had compilation or runtime errors. Fix the issues
below while keeping ALL hard constraints.

Compilation / runtime errors:
{errors}

Previously generated test code:
{previous_code}

The complete project source code (for reference):
{projectSourceCode}

[... same hard constraints as above ...]
```

## Validation JSON Format

The `--targets` JSON file uses the same format as the original pipeline's input JSON. It can be a single object or an array. Nested `fullMethodsPaths` structures are supported. The key fields used for validation:

```json
[
  {
    "entryPoint": "com.example.Service.process",
    "thirdPartyMethod": "org.apache.commons.lang3.StringUtils.isEmpty",
    "directCaller": "com.example.Service.process",
    "path": [
      "com.example.Service.process",
      "org.apache.commons.lang3.StringUtils.isEmpty"
    ]
  }
]
```

Only `thirdPartyMethod` and the path are required for validation. The rest is informational.

## Output

### Console output

```
[Validate] 12/20 targets covered

RESULTS SUMMARY
================================================================================
Tests compiled/ran successfully: True
Iterations used: 2
Test classes generated: 15
Dependency call sites covered: 12/20 (60.0%)

Covered (12):
  + org.apache.commons.lang3.StringUtils.isEmpty
  + ...

Not covered (8):
  - com.fasterxml.jackson.databind.ObjectMapper.readValue
  - ...
================================================================================
```

### JSON output (`--output-json`)

```json
{
  "success": true,
  "iterations": 2,
  "test_classes_generated": 15,
  "covered_count": 12,
  "total_targets": 20,
  "coverage_rate": 0.6,
  "validation_results": [
    {
      "third_party_method": "org.apache.commons.lang3.StringUtils.isEmpty",
      "entry_point": "com.example.Service.process",
      "covered": true,
      "error": null
    }
  ],
  "test_class_names": ["Reachability_Service_process_1Test", "..."]
}
```
