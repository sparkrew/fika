from __future__ import annotations

import argparse
import json
import logging
import sys
from datetime import datetime
from pathlib import Path
from time import perf_counter

from junit_agent.hf_model import build_local_hf_llm
from junit_agent.deepseek_model import build_deepseek_llm, build_openrouter_llm
from junit_agent.graph_app import AppConfig
from junit_agent.pure_llm_graph import (
    PureLLMInput,
    build_pure_llm_graph,
    load_validation_targets,
    pure_llm_initial_state,
)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="pure-llm-junit-agent",
        description=(
            "Generate JUnit 5 reachability tests using a pure-LLM approach. "
            "The LLM receives all project source code and generates tests for "
            "every third-party dependency call site it can find."
        ),
    )
    p.add_argument("repo_root", type=str, help="Path to the target Maven Java project")
    p.add_argument(
        "--targets",
        type=str,
        required=True,
        help="Path to JSON file listing third-party method targets (used for validation only)",
    )
    p.add_argument(
        "--test-package",
        type=str,
        default="generated",
        help="Package name for generated test classes (default: generated)",
    )
    p.add_argument("--mvn", type=str, default="mvn", help="Maven command (default: mvn)")
    p.add_argument(
        "--model",
        type=str,
        default="Qwen/Qwen2.5-1.5B-Instruct",
        help="Model id (HuggingFace model or 'deepseek-chat' for DeepSeek)",
    )
    p.add_argument(
        "--api",
        type=str,
        choices=["hf", "deepseek", "openrouter"],
        default="hf",
        help="API to use: 'hf' for HuggingFace (local), 'deepseek' for DeepSeek API, or 'openrouter' for OpenRouter API",
    )
    p.add_argument("--api-key", type=str, help="API key for DeepSeek/OpenRouter (or set DEEPSEEK_API_KEY / OPENROUTER_API_KEY env var)")
    p.add_argument("--iters", type=int, default=5, help="Max generate-run iterations (default: 5)")
    p.add_argument("--temp", type=float, default=0.1, help="Sampling temperature")
    p.add_argument("--max-new", type=int, default=4096, help="Max new tokens")
    p.add_argument("--run-all", action="store_true", help="Run full mvn test suite (default runs only generated tests)")
    p.add_argument("--output-json", type=str, help="Save final results to JSON file")
    p.add_argument("--log-file", type=str, help="Append detailed text logs to this file")
    return p.parse_args()


def format_elapsed_time(seconds: float) -> str:
    total_seconds = max(0.0, seconds)
    hours, remainder = divmod(int(total_seconds), 3600)
    minutes, whole_seconds = divmod(remainder, 60)
    milliseconds = int((total_seconds - int(total_seconds)) * 1000)
    return f"{hours:02d}:{minutes:02d}:{whole_seconds:02d}.{milliseconds:03d}"


def main() -> int:
    args = parse_args()
    start_time = perf_counter()

    repo_root = Path(args.repo_root).resolve()
    targets_path = Path(args.targets).resolve()

    # -----------------------------------------------------------------------
    # Load validation targets
    # -----------------------------------------------------------------------
    targets = load_validation_targets(targets_path)
    print(f"Loaded {len(targets)} validation targets from {targets_path}")

    # -----------------------------------------------------------------------
    # Build LLM
    # -----------------------------------------------------------------------
    if args.api == "deepseek":
        model_name = args.model if args.model != "Qwen/Qwen2.5-1.5B-Instruct" else "deepseek-chat"
        llm = build_deepseek_llm(
            api_key=args.api_key,
            temperature=args.temp,
            max_tokens=args.max_new,
            model=model_name,
        )
        print(f"Using DeepSeek API with model: {model_name}")
    elif args.api == "openrouter":
        model_name = args.model if args.model != "Qwen/Qwen2.5-1.5B-Instruct" else "deepseek/deepseek-v3.2"
        llm = build_openrouter_llm(
            api_key=args.api_key,
            temperature=args.temp,
            max_tokens=args.max_new,
            model=model_name,
        )
        print(f"Using OpenRouter API with model: {model_name}")
    else:
        llm = build_local_hf_llm(
            model_id=args.model,
            temperature=args.temp,
            max_new_tokens=args.max_new,
        )
        print(f"Using local HuggingFace model: {args.model}")

    # -----------------------------------------------------------------------
    # Build graph and initial state
    # -----------------------------------------------------------------------
    cfg = AppConfig(
        repo_root=repo_root,
        mvn_cmd=args.mvn,
        max_iterations=args.iters,
        run_only_generated_test=(not args.run_all),
    )

    inp = PureLLMInput(test_package=args.test_package)
    app = build_pure_llm_graph(llm=llm, cfg=cfg)
    state = pure_llm_initial_state(cfg, inp, targets)

    # -----------------------------------------------------------------------
    # Logging
    # -----------------------------------------------------------------------
    logger = logging.getLogger("junit_agent.pure_llm")
    logger.setLevel(logging.DEBUG)
    ch = logging.StreamHandler()
    ch.setLevel(logging.INFO)
    ch.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    logger.addHandler(ch)

    text_log_fh = None
    if args.log_file:
        text_log_fh = open(args.log_file, "a", encoding="utf-8")
        text_log_fh.write("\n" + "=" * 100 + "\n")
        text_log_fh.write(f"PURE-LLM LOG SESSION: {datetime.now().isoformat()}\n")
        text_log_fh.write("=" * 100 + "\n\n")

    def _tlog(line: str) -> None:
        if text_log_fh:
            text_log_fh.write(line + "\n")
            text_log_fh.flush()

    # -----------------------------------------------------------------------
    # Run the graph
    # -----------------------------------------------------------------------
    final_state = None
    print("\n" + "=" * 80)
    print("PURE-LLM REACHABILITY TEST GENERATION")
    print(f"Project: {repo_root}")
    print(f"Validation targets: {len(targets)}")
    print("=" * 80 + "\n")

    recursion_limit = (args.iters * 6) + 10
    try:
        for event in app.stream(state, config={"recursion_limit": recursion_limit}):
            for node, st in event.items():
                trace = st.get("trace", [])
                if trace:
                    print(trace[-1])
                    _tlog(trace[-1])
                final_state = st
    except KeyboardInterrupt:
        print("\n!!! INTERRUPTED !!!")
    except Exception as e:
        print(f"\n!!! ERROR: {e}")
        import traceback
        traceback.print_exc()

    # -----------------------------------------------------------------------
    # Print summary
    # -----------------------------------------------------------------------
    print("\n" + "=" * 80)
    print("RESULTS SUMMARY")
    print("=" * 80)

    if final_state:
        covered = final_state.get("covered_count", 0)
        total = final_state.get("total_targets", 0)
        success = final_state.get("success", False)
        iterations = final_state.get("iteration", 0)
        test_classes = final_state.get("test_classes", [])

        print(f"Tests compiled/ran successfully: {success}")
        print(f"Iterations used: {iterations}")
        print(f"Test classes generated: {len(test_classes)}")
        print(f"Dependency call sites covered: {covered}/{total}", end="")
        if total > 0:
            print(f" ({covered / total * 100:.1f}%)")
        else:
            print()

        # List covered and uncovered targets
        results = final_state.get("validation_results", [])
        if results:
            covered_list = [r for r in results if r.get("covered")]
            uncovered_list = [r for r in results if not r.get("covered")]

            if covered_list:
                print(f"\nCovered ({len(covered_list)}):")
                for r in covered_list:
                    print(f"  + {r['third_party_method']}")

            if uncovered_list:
                print(f"\nNot covered ({len(uncovered_list)}):")
                for r in uncovered_list:
                    print(f"  - {r['third_party_method']}")

        # Token usage summary
        token_log = final_state.get("token_usage_log", [])
        if token_log:
            input_tokens_list = [u.get("input_tokens", 0) for u in token_log if u.get("input_tokens")]
            output_tokens_list = [u.get("output_tokens", 0) for u in token_log if u.get("output_tokens")]
            total_input = sum(input_tokens_list)
            total_output = sum(output_tokens_list)
            num_calls = len(input_tokens_list)

            print(f"\nToken Usage ({num_calls} LLM call(s)):")
            print(f"  Total input tokens:  {total_input:,}")
            print(f"  Total output tokens: {total_output:,}")
            print(f"  Total tokens:        {total_input + total_output:,}")
            if num_calls > 0:
                print(f"  Avg input tokens:    {total_input / num_calls:,.0f}")
                print(f"  Avg output tokens:   {total_output / num_calls:,.0f}")

            for i, u in enumerate(token_log, 1):
                inp_t = u.get("input_tokens", 0)
                out_t = u.get("output_tokens", 0)
                print(f"  Iteration {i}: input={inp_t:,}  output={out_t:,}  total={inp_t + out_t:,}")

        # Save results to JSON
        if args.output_json:
            token_summary = {}
            if token_log:
                token_summary = {
                    "total_input_tokens": total_input,
                    "total_output_tokens": total_output,
                    "total_tokens": total_input + total_output,
                    "avg_input_tokens": total_input / num_calls if num_calls > 0 else 0,
                    "avg_output_tokens": total_output / num_calls if num_calls > 0 else 0,
                    "per_iteration": token_log,
                }
            output_data = {
                "success": success,
                "iterations": iterations,
                "test_classes_generated": len(test_classes),
                "covered_count": covered,
                "total_targets": total,
                "coverage_rate": covered / total if total > 0 else 0,
                "token_usage": token_summary,
                "validation_results": results,
                "test_class_names": [c["class_name"] for c in test_classes],
            }
            Path(args.output_json).write_text(
                json.dumps(output_data, indent=2, ensure_ascii=False), encoding="utf-8"
            )
            print(f"\nResults saved to: {args.output_json}")
    else:
        print("No final state produced.")

    print("=" * 80)

    # Cleanup
    if text_log_fh:
        text_log_fh.close()

    elapsed = perf_counter() - start_time
    print(f"Total elapsed time: {format_elapsed_time(elapsed)}")

    if final_state and final_state.get("success", False):
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
