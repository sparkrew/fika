from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

from langchain_core.prompts import PromptTemplate
from langchain_core.runnables import Runnable

from junit_agent.prompts import (
    PURE_LLM_SYSTEM_PROMPT,
    PURE_LLM_USER_PROMPT_TEMPLATE,
    PURE_LLM_RETRY_PROMPT_TEMPLATE,
)
from junit_agent.extract_java_sources import extract_java_sources
from junit_agent.tools import run_maven_test, write_test, get_coverage_result, cleanup_test
from junit_agent.graph_app import AppConfig, _extract_java_code, _format_feedback


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class PureLLMInput:
    test_package: str = "generated"


@dataclass(frozen=True)
class ValidationTarget:
    entry_point: str
    third_party_method: str
    direct_caller: str
    path: List[str] = field(default_factory=list)


@dataclass
class FileResult:
    source_file: str
    test_classes: List[Dict[str, str]]
    success: bool
    iterations_used: int
    token_usage: List[Dict[str, int]]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _split_test_classes(generated_code: str) -> List[Dict[str, str]]:
    separator = "// --- NEW TEST CLASS ---"
    chunks = generated_code.split(separator)

    classes = []
    for chunk in chunks:
        chunk = chunk.strip()
        if not chunk:
            continue

        class_match = re.search(r"\bclass\s+(\w+)", chunk)
        if class_match:
            class_name = class_match.group(1)
        else:
            class_name = f"GeneratedTest_{len(classes) + 1}"

        classes.append({"class_name": class_name, "source": chunk})

    return classes


def _java_rel_path(test_package: str, class_name: str) -> str:
    pkg_path = test_package.replace(".", "/")
    return f"src/test/java/{pkg_path}/{class_name}.java"


def _extract_token_usage(response: Any) -> Dict[str, int]:
    usage: Dict[str, int] = {}
    if hasattr(response, "response_metadata"):
        meta = response.response_metadata or {}
        token_usage = meta.get("token_usage") or meta.get("usage") or {}
        usage = {
            "input_tokens": token_usage.get("prompt_tokens", 0),
            "output_tokens": token_usage.get("completion_tokens", 0),
            "total_tokens": token_usage.get("total_tokens", 0),
        }
    return usage


def load_validation_targets(json_path: Path) -> List[ValidationTarget]:
    data = json.loads(json_path.read_text(encoding="utf-8"))

    if isinstance(data, dict):
        items = data.get("fullMethodsPaths", [data])
    elif isinstance(data, list):
        items_nested: List[dict] = []
        for entry in data:
            if "fullMethodsPaths" in entry:
                items_nested.extend(entry["fullMethodsPaths"])
            else:
                items_nested.append(entry)
        items = items_nested
    else:
        items = []

    targets = []
    for item in items:
        targets.append(ValidationTarget(
            entry_point=item.get("entryPoint", ""),
            third_party_method=item.get("thirdPartyMethod", ""),
            direct_caller=item.get("directCaller", item.get("entryPoint", "")),
            path=list(item.get("path", [])),
        ))
    return targets


def validate_coverage(
    repo_root: Path,
    targets: List[ValidationTarget],
) -> tuple[int, int, List[Dict[str, Any]]]:
    """Check each validation target against JaCoCo coverage. Returns (covered, total, details)."""
    results = []
    covered = 0

    for t in targets:
        path = t.path
        tp_method = t.third_party_method

        if len(path) >= 2:
            method_class = path[-2].rsplit(".", 1)[0]
        elif t.direct_caller:
            method_class = t.direct_caller.rsplit(".", 1)[0]
        else:
            method_class = t.entry_point.rsplit(".", 1)[0]

        cov = get_coverage_result(
            repo_root=repo_root,
            method_class=method_class,
            target_method=tp_method,
        )

        is_covered = cov.method_covered
        if is_covered:
            covered += 1

        results.append({
            "third_party_method": tp_method,
            "entry_point": t.entry_point,
            "direct_caller": t.direct_caller,
            "method_class": method_class,
            "covered": is_covered,
            "error": cov.error,
        })

    return covered, len(targets), results


# ---------------------------------------------------------------------------
# Per-file generate + retry loop (no LangGraph needed)
# ---------------------------------------------------------------------------

def process_single_file(
    llm: Runnable,
    cfg: AppConfig,
    source_file_path: str,
    class_source_code: str,
    test_package: str,
    max_iters: int,
    log_fn=None,
) -> FileResult:
    """Generate reachability tests for a single Java source file with retry on errors."""

    first_prompt = PromptTemplate(
        input_variables=["classSourceCode", "source_file_path", "test_package"],
        template=PURE_LLM_SYSTEM_PROMPT + "\n\n" + PURE_LLM_USER_PROMPT_TEMPLATE,
    )

    retry_prompt = PromptTemplate(
        input_variables=["errors", "previous_code", "classSourceCode", "source_file_path", "test_package"],
        template=PURE_LLM_SYSTEM_PROMPT + "\n\n" + PURE_LLM_RETRY_PROMPT_TEMPLATE,
    )

    def _log(msg: str) -> None:
        if log_fn:
            log_fn(msg)

    token_usage_log: List[Dict[str, int]] = []
    last_error = ""
    last_code = ""
    test_classes: List[Dict[str, str]] = []
    success = False

    for it in range(1, max_iters + 1):
        _log(f"  [Generate] iteration {it}/{max_iters}")

        if it == 1:
            rendered = first_prompt.format(
                classSourceCode=class_source_code,
                source_file_path=source_file_path,
                test_package=test_package,
            )
        else:
            rendered = retry_prompt.format(
                errors=last_error,
                previous_code=last_code,
                classSourceCode=class_source_code,
                source_file_path=source_file_path,
                test_package=test_package,
            )

        response = llm.invoke(rendered)
        usage = _extract_token_usage(response)
        token_usage_log.append(usage)

        if hasattr(response, "content"):
            raw = response.content
        elif not isinstance(response, str):
            raw = str(response)
        else:
            raw = response

        raw = _extract_java_code(raw)
        last_code = raw

        classes = _split_test_classes(raw)
        for cls in classes:
            cls["rel_path"] = _java_rel_path(test_package, cls["class_name"])
        test_classes = classes

        token_msg = ""
        if usage.get("input_tokens"):
            token_msg = f" (input={usage['input_tokens']:,}, output={usage['output_tokens']:,})"
        _log(f"  [Generate] produced {len(classes)} test class(es){token_msg}")

        if not classes:
            _log("  [Generate] no test classes produced, skipping")
            break

        # Write test files
        repo = Path(cfg.repo_root)
        for cls in classes:
            write_test(repo, cls["rel_path"], cls["source"])

        # Run Maven
        _log("  [Run] compiling and running tests")
        if cfg.run_only_generated_test:
            test_fqcns = [f"{test_package}.{cls['class_name']}" for cls in classes]
            test_filter = "+".join(test_fqcns)
        else:
            test_filter = None

        rr = run_maven_test(
            repo_root=repo,
            mvn_cmd=cfg.mvn_cmd,
            test_fqcn=test_filter,
            timeout_s=600,
        )

        if rr.success:
            _log(f"  [Run] success — tests passed on iteration {it}")
            success = True
            break
        else:
            formatted = _format_feedback(rr.combined)
            last_error = formatted
            retry_msg = "will retry" if it < max_iters else "no retries left"
            _log(f"  [Run] failed (exit={rr.exit_code}), {retry_msg}")
            # Print error details (truncated for readability)
            if formatted:
                error_preview = formatted[:3000]
                _log(f"  [Run] Error details:\n{error_preview}")
                if len(formatted) > 3000:
                    _log(f"  ... (truncated, {len(formatted)} chars total)")

    if not success:
        repo = Path(cfg.repo_root)
        for cls in test_classes:
            try:
                cleanup_test(repo / cls["rel_path"])
            except Exception:
                pass
        _log("  [Cleanup] removed unsuccessful test files")

    return FileResult(
        source_file=source_file_path,
        test_classes=test_classes if success else [],
        success=success,
        iterations_used=min(it, max_iters) if 'it' in dir() else 0,
        token_usage=token_usage_log,
    )
