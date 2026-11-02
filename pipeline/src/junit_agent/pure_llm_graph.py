from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, TypedDict

from langchain_core.prompts import PromptTemplate
from langchain_core.runnables import Runnable
from langgraph.graph import StateGraph, END

from junit_agent.prompts import (
    PURE_LLM_SYSTEM_PROMPT,
    PURE_LLM_USER_PROMPT_TEMPLATE,
    PURE_LLM_RETRY_PROMPT_TEMPLATE,
)
from junit_agent.extract_java_sources import extract_java_sources
from junit_agent.tools import run_maven_test, write_test, get_coverage_result, cleanup_test
from junit_agent.graph_app import (
    AppConfig,
    _extract_java_code,
    _format_feedback,
    _extract_maven_errors,
    _extract_compilation_errors,
)


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


class PureLLMState(TypedDict, total=False):
    # config
    repo_root: str
    mvn_cmd: str
    max_iterations: int
    run_only_generated_test: bool
    test_package: str

    # source code (extracted once)
    project_source_code: str
    source_file_paths: List[str]

    # loop state
    iteration: int
    generated_code: str
    test_classes: List[Dict[str, str]]  # [{class_name, source, rel_path}]
    last_run_output: str
    last_exit_code: int
    success: bool
    trace: List[str]

    # validation
    validation_targets: List[Dict[str, Any]]
    validation_results: List[Dict[str, Any]]
    covered_count: int
    total_targets: int

    iteration_log: List[Dict[str, Any]]
    last_prompt: str


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _split_test_classes(generated_code: str) -> List[Dict[str, str]]:
    """Split LLM output into individual test class sources."""
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


def load_validation_targets(json_path: Path) -> List[ValidationTarget]:
    """Load third-party method targets from JSON for coverage validation."""
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


# ---------------------------------------------------------------------------
# Graph builder
# ---------------------------------------------------------------------------

def build_pure_llm_graph(llm: Runnable, cfg: AppConfig) -> Any:
    """Build a LangGraph for the pure-LLM reachability test generation flow."""

    first_prompt = PromptTemplate(
        input_variables=["projectSourceCode", "test_package"],
        template=PURE_LLM_SYSTEM_PROMPT + "\n\n" + PURE_LLM_USER_PROMPT_TEMPLATE,
    )

    retry_prompt = PromptTemplate(
        input_variables=["errors", "previous_code", "projectSourceCode", "test_package"],
        template=PURE_LLM_SYSTEM_PROMPT + "\n\n" + PURE_LLM_RETRY_PROMPT_TEMPLATE,
    )

    # -- nodes ---------------------------------------------------------------

    def node_extract(state: PureLLMState) -> PureLLMState:
        """Extract all Java source files from the project."""
        state.setdefault("trace", []).append("[Extract] reading project source files")
        source_code, file_paths = extract_java_sources(Path(state["repo_root"]))
        state["project_source_code"] = source_code
        state["source_file_paths"] = file_paths
        state.setdefault("trace", []).append(
            f"[Extract] found {len(file_paths)} Java source files"
        )
        return state

    def node_generate(state: PureLLMState) -> PureLLMState:
        it = int(state.get("iteration", 0)) + 1
        state["iteration"] = it
        state.setdefault("trace", []).append(f"[Generate] iteration={it}")

        if it == 1 or not state.get("last_run_output"):
            rendered = first_prompt.format(
                projectSourceCode=state["project_source_code"],
                test_package=state["test_package"],
            )
        else:
            rendered = retry_prompt.format(
                errors=state.get("last_run_output", ""),
                previous_code=state.get("generated_code", ""),
                projectSourceCode=state["project_source_code"],
                test_package=state["test_package"],
            )

        state["last_prompt"] = rendered

        response = llm.invoke(rendered)
        if hasattr(response, "content"):
            raw = response.content
        elif not isinstance(response, str):
            raw = str(response)
        else:
            raw = response

        raw = _extract_java_code(raw)
        state["generated_code"] = raw

        classes = _split_test_classes(raw)
        for cls in classes:
            cls["rel_path"] = _java_rel_path(state["test_package"], cls["class_name"])
        state["test_classes"] = classes

        state.setdefault("trace", []).append(
            f"[Generate] produced {len(classes)} test class(es), {len(raw)} chars"
        )

        state.setdefault("iteration_log", [])
        state["iteration_log"].append({
            "iteration": it,
            "prompt_length": len(rendered),
            "generated_code_length": len(raw),
            "test_class_count": len(classes),
            "test_class_names": [c["class_name"] for c in classes],
        })

        return state

    def node_write(state: PureLLMState) -> PureLLMState:
        state.setdefault("trace", []).append("[Write] writing test files")
        repo = Path(state["repo_root"])
        for cls in state.get("test_classes", []):
            abs_path = write_test(repo, cls["rel_path"], cls["source"])
            state.setdefault("trace", []).append(f"[Write] wrote {abs_path}")
        return state

    def node_run(state: PureLLMState) -> PureLLMState:
        state.setdefault("trace", []).append("[Run] running Maven tests")
        repo = Path(state["repo_root"])

        if state.get("run_only_generated_test", True) and state.get("test_classes"):
            test_fqcns = [
                f"{state['test_package']}.{cls['class_name']}"
                for cls in state["test_classes"]
            ]
            test_filter = "+".join(test_fqcns)
        else:
            test_filter = None

        rr = run_maven_test(
            repo_root=repo,
            mvn_cmd=state["mvn_cmd"],
            test_fqcn=test_filter,
            timeout_s=600,
        )

        formatted = _format_feedback(rr.combined)
        state["last_run_output"] = formatted
        state["success"] = rr.success
        state["last_exit_code"] = rr.exit_code
        state.setdefault("trace", []).append(
            f"[Run] success={rr.success} exit={rr.exit_code}"
        )

        if state.get("iteration_log"):
            state["iteration_log"][-1]["maven_success"] = rr.success
            state["iteration_log"][-1]["maven_exit_code"] = rr.exit_code

        return state

    def node_validate(state: PureLLMState) -> PureLLMState:
        """Check coverage of each validation target against JaCoCo results."""
        state.setdefault("trace", []).append("[Validate] checking target coverage")
        targets = state.get("validation_targets", [])
        repo = Path(state["repo_root"])

        results = []
        covered = 0

        for t in targets:
            path = t.get("path", [])
            tp_method = t["third_party_method"]

            if len(path) >= 2:
                method_class = path[-2].rsplit(".", 1)[0]
            elif t.get("direct_caller"):
                method_class = t["direct_caller"].rsplit(".", 1)[0]
            else:
                method_class = t.get("entry_point", "").rsplit(".", 1)[0]

            cov = get_coverage_result(
                repo_root=repo,
                method_class=method_class,
                target_method=tp_method,
            )

            is_covered = cov.method_covered
            if is_covered:
                covered += 1

            results.append({
                "third_party_method": tp_method,
                "entry_point": t.get("entry_point", ""),
                "direct_caller": t.get("direct_caller", ""),
                "method_class": method_class,
                "covered": is_covered,
                "error": cov.error,
            })

        state["validation_results"] = results
        state["covered_count"] = covered
        state["total_targets"] = len(targets)

        state.setdefault("trace", []).append(
            f"[Validate] {covered}/{len(targets)} targets covered"
        )

        if state.get("iteration_log"):
            state["iteration_log"][-1]["covered_count"] = covered
            state["iteration_log"][-1]["total_targets"] = len(targets)

        return state

    def node_decide(state: PureLLMState) -> PureLLMState:
        it = int(state.get("iteration", 0))
        max_it = int(state.get("max_iterations", cfg.max_iterations))
        tests_passed = bool(state.get("success", False))

        if tests_passed:
            state.setdefault("trace", []).append(
                f"[Decide] tests passed — done (iteration {it})"
            )
        elif it >= max_it:
            state.setdefault("trace", []).append(
                f"[Decide] max iterations reached ({max_it}) — stopping"
            )
        else:
            state.setdefault("trace", []).append(
                f"[Decide] tests failed — will retry (iteration {it}/{max_it})"
            )

        if state.get("iteration_log"):
            state["iteration_log"][-1]["decision"] = (
                "done" if tests_passed else ("retry" if it < max_it else "max_iter")
            )

        return state

    def route_after_decide(state: PureLLMState) -> str:
        if state.get("success", False):
            return "validate"
        if int(state.get("iteration", 0)) >= int(
            state.get("max_iterations", cfg.max_iterations)
        ):
            return "validate"
        return "generate"

    def node_finalize(state: PureLLMState) -> PureLLMState:
        covered = state.get("covered_count", 0)
        total = state.get("total_targets", 0)
        state.setdefault("trace", []).append(
            f"[Finalize] {covered}/{total} dependency call sites covered"
        )

        if not state.get("success", False):
            repo = Path(state["repo_root"])
            for cls in state.get("test_classes", []):
                try:
                    cleanup_test(repo / cls["rel_path"])
                except Exception:
                    pass
            state.setdefault("trace", []).append(
                "[Finalize] cleaned up unsuccessful test files"
            )

        return state

    # -- graph wiring --------------------------------------------------------

    sg = StateGraph(PureLLMState)
    sg.add_node("extract", node_extract)
    sg.add_node("generate", node_generate)
    sg.add_node("write", node_write)
    sg.add_node("run", node_run)
    sg.add_node("decide", node_decide)
    sg.add_node("validate", node_validate)
    sg.add_node("finalize", node_finalize)

    sg.set_entry_point("extract")
    sg.add_edge("extract", "generate")
    sg.add_edge("generate", "write")
    sg.add_edge("write", "run")
    sg.add_edge("run", "decide")
    sg.add_conditional_edges(
        "decide",
        route_after_decide,
        {"generate": "generate", "validate": "validate"},
    )
    sg.add_edge("validate", "finalize")
    sg.add_edge("finalize", END)

    return sg.compile()


def pure_llm_initial_state(
    cfg: AppConfig,
    inp: PureLLMInput,
    targets: List[ValidationTarget],
) -> PureLLMState:
    return PureLLMState(
        repo_root=str(cfg.repo_root.resolve()),
        mvn_cmd=cfg.mvn_cmd,
        max_iterations=cfg.max_iterations,
        run_only_generated_test=cfg.run_only_generated_test,
        test_package=inp.test_package,
        project_source_code="",
        source_file_paths=[],
        iteration=0,
        generated_code="",
        test_classes=[],
        last_run_output="",
        last_exit_code=0,
        success=False,
        trace=[],
        validation_targets=[
            {
                "entry_point": t.entry_point,
                "third_party_method": t.third_party_method,
                "direct_caller": t.direct_caller,
                "path": t.path,
            }
            for t in targets
        ],
        validation_results=[],
        covered_count=0,
        total_targets=len(targets),
        iteration_log=[],
        last_prompt="",
    )
