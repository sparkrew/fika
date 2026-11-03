# Pure-LLM Reachability Scenario Generation Pipeline

This document describes the **pure-LLM** pipeline for generating JUnit 5 reachability scenarios. Unlike the original pipeline (which reads pre-extracted method data from a JSON file and feeds it to the LLM), this approach gives the LLM **one Java source file at a time** and asks it to identify and generate reachability scenarios for third-party dependency call sites in that file.

## Overview

| Aspect | Original pipeline | Pure-LLM pipeline |
|---|---|---|
| LLM input | Structured JSON (entryPoint, methodSources, constructors, etc.) | One `.java` source file per LLM call |
| LLM task | Generate one scenario for one specific call path | Identify all dependency call sites in a class and generate scenarios for each |
| JSON file role | Drives prompting (tells the LLM what to test) | Validation only (used to measure how many targets the LLM covered) |
| Feedback loop | Compilation errors + coverage gaps | Compilation/runtime errors only |
| Context usage | Small (one call chain) | Moderate (one class at a time, stays within context limits) |

## Pipeline Flow

```
┌──────────────────┐
│  1. Extract       │  Walk project, read all .java files (excl. src/test/)
└────────┬─────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────┐
│  2. For each source file:                                    │
│                                                              │
│   ┌─────────────┐                                            │
│   │  Generate    │  Prompt LLM with this file's source code  │
│   └──────┬──────┘                                            │
│          │                                                   │
│          ▼                                                   │
│   ┌─────────────┐                                            │
│   │  Write       │  Write generated scenario classes         │
│   └──────┬──────┘                                            │
│          │                                                   │
│          ▼                                                   │
│   ┌─────────────┐                                            │
│   │  Run Maven   │  Compile + test                           │
│   └──────┬──────┘                                            │
│          │                                                   │
│          ├── success ──▶ keep scenarios, next file            │
│          │                                                   │
│          └── failure ──▶ retry with error feedback            │
│                         (up to --iters times)                │
│                         then cleanup, next file              │
└──────────────────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────┐
│  3. Final build   │  Run all successful scenarios together
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  4. Validate      │  Check JSON targets against JaCoCo coverage
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  5. Report        │  X/N targets covered + token usage
└──────────────────┘
```

### Step 1: Extract

Walks the project directory and reads every `.java` file, **excluding** anything under `src/test/`. Returns a map of relative file paths to their contents.

**Module:** `extract_java_sources.py`

### Step 2: Per-file generate + retry loop

For **each Java source file**, the LLM receives that file's source code and is asked to:

1. Identify all third-party / dependency method call sites in this class
2. Generate a separate JUnit 5 reachability scenario for each call site

If the generated scenarios **fail to compile or run**, the LLM receives the Maven error output and the previous code, and is asked to fix them. This retries up to `--iters` times.

If the scenarios **pass**, they are kept and the pipeline moves to the next file. If all retries are exhausted, the failed scenarios are cleaned up.

This class-by-class approach keeps each prompt small and well within LLM context limits, even for large projects.

**Module:** `pure_llm_graph.py` → `process_single_file()`

### Step 3: Final build

After processing all files, runs Maven once more with all the successfully generated scenario classes to ensure they work together.

### Step 4: Validate

Loads the JSON targets file and checks each `(thirdPartyMethod, directCaller)` pair against JaCoCo coverage data. Reports:

- Total call sites in the JSON: **N**
- Covered by the generated scenarios: **X**
- Coverage rate: **X/N**

Each target is checked using the existing `get_coverage_result()` function which parses JaCoCo XML reports.

### Step 5: Report

Prints the coverage summary and token usage statistics (total, average per call, per-file breakdown).

## Usage

```bash
python -m junit_agent.main_pure_llm /path/to/java/project \
    --targets targets.json \
    --api openrouter \
    --model deepseek/deepseek-v3.2 \
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
| `--test-package` | No | Package for generated scenarios (default: `generated`) |
| `--api` | No | `hf` (local HuggingFace), `deepseek`, or `openrouter` (default: `hf`) |
| `--model` | No | Model identifier |
| `--api-key` | No | API key for DeepSeek/OpenRouter |
| `--iters` | No | Max retry iterations per file (default: 5) |
| `--temp` | No | Sampling temperature (default: 0.1) |
| `--max-new` | No | Max new tokens (default: 4096) |
| `--run-all` | No | Run full Maven test suite instead of only generated scenarios |
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

### Per-file User Prompt

```
You are given the source code of a single Java class from a project. Your task is to
generate JUnit 5 reachability scenarios that exercise as many third-party / dependency
method call sites in this class as possible.

A "reachability scenario" is a piece of code whose only goal is to invoke a specific
third-party method during execution. No assertions, inspections, or verifications are
required.

A "third-party / dependency method" is any method that belongs to an external library
(not defined in this project's own source code). Examples include calls to Apache
Commons, Google Guava, Jackson, SLF4J, database drivers, HTTP clients, etc.

Instructions:
1. Analyze the provided source code of this class.
2. Identify all call sites where a third-party / dependency method is invoked.
3. For each such call site, generate a separate JUnit 5 test class that triggers
   execution of that call site.
4. Each test class should have exactly ONE @Test method.
5. Name each test class descriptively:
   Reachability_<ClassName>_<MethodName>_<index>Test

Source file: {source_file_path}

{classSourceCode}

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
The previously generated scenarios had compilation or runtime errors. Fix the issues
below while keeping ALL hard constraints.

Compilation / runtime errors:
{errors}

Previously generated test code:
{previous_code}

Original source file: {source_file_path}

{classSourceCode}

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
RESULTS SUMMARY
================================================================================
Source files processed: 25/25
Files with successful scenarios: 20
Total scenario classes generated: 48
Final Maven build: passed
Dependency call sites covered: 12/20 (60.0%)

Token Usage (30 LLM call(s)):
  Total input tokens:  125,000
  Total output tokens: 45,000
  Total tokens:        170,000
  Avg input tokens:    4,167
  Avg output tokens:   1,500

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
  "source_files_processed": 25,
  "files_with_successful_scenarios": 20,
  "scenario_classes_generated": 48,
  "final_maven_success": true,
  "covered_count": 12,
  "total_targets": 20,
  "coverage_rate": 0.6,
  "token_usage": {
    "total_input_tokens": 125000,
    "total_output_tokens": 45000,
    "total_tokens": 170000,
    "avg_input_tokens": 4167,
    "avg_output_tokens": 1500,
    "num_llm_calls": 30
  },
  "per_file_results": [
    {
      "source_file": "src/main/java/com/example/Service.java",
      "success": true,
      "iterations_used": 1,
      "scenario_classes": ["Reachability_Service_process_1Test"],
      "token_usage": [{"input_tokens": 5000, "output_tokens": 1500, "total_tokens": 6500}]
    }
  ],
  "validation_results": [
    {
      "third_party_method": "org.apache.commons.lang3.StringUtils.isEmpty",
      "entry_point": "com.example.Service.process",
      "covered": true,
      "error": null
    }
  ],
  "scenario_class_names": ["Reachability_Service_process_1Test"]
}
```
