from __future__ import annotations

import argparse
import json
import logging
from datetime import datetime
from pathlib import Path
from time import perf_counter

from junit_agent.hf_model import build_local_hf_llm
from junit_agent.deepseek_model import build_deepseek_llm, build_openrouter_llm
from junit_agent.graph_app import AppConfig
from junit_agent.extract_java_sources import extract_java_sources
from junit_agent.pure_llm_graph import (
    PureLLMInput,
    load_validation_targets,
    validate_coverage,
    process_single_file,
)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="pure-llm-junit-agent",
        description=(
            "Generate JUnit 5 reachability tests using a pure-LLM approach. "
            "The LLM receives each Java source file one at a time and generates "
            "tests for third-party dependency call sites in that file."
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
        help="Model id",
    )
    p.add_argument(
        "--api",
        type=str,
        choices=["hf", "deepseek", "openrouter"],
        default="hf",
        help="API to use: 'hf' for HuggingFace (local), 'deepseek' for DeepSeek, or 'openrouter' for OpenRouter",
    )
    p.add_argument(
        "--api-key",
        type=str,
        help="API key for DeepSeek/OpenRouter (or set env var DEEPSEEK_API_KEY / OPENROUTER_API_KEY)",
    )
    p.add_argument("--iters", type=int, default=5, help="Max retry iterations per file (default: 5)")
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
    # Config
    # -----------------------------------------------------------------------
    cfg = AppConfig(
        repo_root=repo_root,
        mvn_cmd=args.mvn,
        max_iterations=args.iters,
        run_only_generated_test=(not args.run_all),
    )

    # -----------------------------------------------------------------------
    # Logging
    # -----------------------------------------------------------------------
    text_log_fh = None
    if args.log_file:
        text_log_fh = open(args.log_file, "a", encoding="utf-8")
        text_log_fh.write("\n" + "=" * 100 + "\n")
        text_log_fh.write(f"PURE-LLM LOG SESSION: {datetime.now().isoformat()}\n")
        text_log_fh.write("=" * 100 + "\n\n")

    def _log(msg: str) -> None:
        print(msg)
        if text_log_fh:
            text_log_fh.write(msg + "\n")
            text_log_fh.flush()

    # -----------------------------------------------------------------------
    # Extract Java source files
    # -----------------------------------------------------------------------
    _log("[Extract] reading project source files")
    source_block, file_paths = extract_java_sources(repo_root)

    # Build a map of file_path -> content
    file_contents: dict[str, str] = {}
    for java_file in sorted(repo_root.rglob("*.java")):
        rel = str(java_file.relative_to(repo_root))
        if "src/test" in rel:
            continue
        if rel in file_paths:
            try:
                file_contents[rel] = java_file.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue

    _log(f"[Extract] found {len(file_contents)} Java source files to process")

    # -----------------------------------------------------------------------
    # Process each file
    # -----------------------------------------------------------------------
    print("\n" + "=" * 80)
    print("PURE-LLM REACHABILITY TEST GENERATION (class-by-class)")
    print(f"Project: {repo_root}")
    print(f"Source files: {len(file_contents)}")
    print(f"Validation targets: {len(targets)}")
    print(f"Max retries per file: {args.iters}")
    print("=" * 80 + "\n")

    all_results = []
    all_token_usage = []
    all_test_classes = []
    files_succeeded = 0

    for idx, (rel_path, content) in enumerate(file_contents.items(), 1):
        _log(f"\n{'='*80}")
        _log(f"[{idx}/{len(file_contents)}] Processing: {rel_path}")
        _log(f"{'='*80}")

        try:
            result = process_single_file(
                llm=llm,
                cfg=cfg,
                source_file_path=rel_path,
                class_source_code=content,
                test_package=args.test_package,
                max_iters=args.iters,
                log_fn=_log,
            )
        except KeyboardInterrupt:
            _log("\n!!! INTERRUPTED !!!")
            break
        except Exception as e:
            _log(f"  [Error] {e}")
            import traceback
            traceback.print_exc()
            continue

        all_results.append(result)
        all_token_usage.extend(result.token_usage)
        if result.success:
            files_succeeded += 1
            all_test_classes.extend(result.test_classes)
            _log(f"  [Done] {len(result.test_classes)} test class(es) generated successfully")

            # Per-file validation: check coverage after this file's tests
            cov_now, total_targets, _ = validate_coverage(repo_root, targets)
            _log(f"  [Coverage] {cov_now}/{total_targets} targets covered so far")
        else:
            _log(f"  [Done] failed after {result.iterations_used} iteration(s)")

    # -----------------------------------------------------------------------
    # Final validation: run all successful tests together + validate
    # -----------------------------------------------------------------------
    _log(f"\n{'='*80}")
    _log("[Final] running all generated tests together + validating coverage")
    _log(f"{'='*80}")

    final_maven_success = False
    if all_test_classes:
        from junit_agent.tools import run_maven_test as _run_mvn
        test_fqcns = [f"{args.test_package}.{cls['class_name']}" for cls in all_test_classes]
        test_filter = "+".join(test_fqcns)
        rr = _run_mvn(
            repo_root=repo_root,
            mvn_cmd=args.mvn,
            test_fqcn=test_filter if not args.run_all else None,
            timeout_s=600,
        )
        final_maven_success = rr.success
        _log(f"[Final] Maven: success={rr.success}, exit={rr.exit_code}")

    covered, total, validation_details = validate_coverage(repo_root, targets)
    _log(f"[Final] {covered}/{total} dependency call sites covered")

    # -----------------------------------------------------------------------
    # Token usage summary
    # -----------------------------------------------------------------------
    input_tokens_list = [u.get("input_tokens", 0) for u in all_token_usage if u.get("input_tokens")]
    output_tokens_list = [u.get("output_tokens", 0) for u in all_token_usage if u.get("output_tokens")]
    total_input = sum(input_tokens_list)
    total_output = sum(output_tokens_list)
    num_calls = len(input_tokens_list)

    # -----------------------------------------------------------------------
    # Print summary
    # -----------------------------------------------------------------------
    print("\n" + "=" * 80)
    print("RESULTS SUMMARY")
    print("=" * 80)

    print(f"Source files processed: {len(all_results)}/{len(file_contents)}")
    print(f"Files with successful tests: {files_succeeded}")
    print(f"Total test classes generated: {len(all_test_classes)}")
    print(f"Final Maven build: {'passed' if final_maven_success else 'failed'}")
    print(f"Dependency call sites covered: {covered}/{total}", end="")
    if total > 0:
        print(f" ({covered / total * 100:.1f}%)")
    else:
        print()

    if num_calls > 0:
        print(f"\nToken Usage ({num_calls} LLM call(s)):")
        print(f"  Total input tokens:  {total_input:,}")
        print(f"  Total output tokens: {total_output:,}")
        print(f"  Total tokens:        {total_input + total_output:,}")
        print(f"  Avg input tokens:    {total_input / num_calls:,.0f}")
        print(f"  Avg output tokens:   {total_output / num_calls:,.0f}")

    # List covered and uncovered
    if validation_details:
        covered_list = [r for r in validation_details if r.get("covered")]
        uncovered_list = [r for r in validation_details if not r.get("covered")]

        if covered_list:
            print(f"\nCovered ({len(covered_list)}):")
            for r in covered_list:
                print(f"  + {r['third_party_method']}")

        if uncovered_list:
            print(f"\nNot covered ({len(uncovered_list)}):")
            for r in uncovered_list:
                print(f"  - {r['third_party_method']}")

    print("=" * 80)

    # -----------------------------------------------------------------------
    # Save results to JSON
    # -----------------------------------------------------------------------
    if args.output_json:
        token_summary = {}
        if num_calls > 0:
            token_summary = {
                "total_input_tokens": total_input,
                "total_output_tokens": total_output,
                "total_tokens": total_input + total_output,
                "avg_input_tokens": total_input / num_calls,
                "avg_output_tokens": total_output / num_calls,
                "num_llm_calls": num_calls,
            }
        output_data = {
            "source_files_processed": len(all_results),
            "files_with_successful_tests": files_succeeded,
            "test_classes_generated": len(all_test_classes),
            "final_maven_success": final_maven_success,
            "covered_count": covered,
            "total_targets": total,
            "coverage_rate": covered / total if total > 0 else 0,
            "token_usage": token_summary,
            "per_file_results": [
                {
                    "source_file": r.source_file,
                    "success": r.success,
                    "iterations_used": r.iterations_used,
                    "test_classes": [c["class_name"] for c in r.test_classes],
                    "token_usage": r.token_usage,
                }
                for r in all_results
            ],
            "validation_results": validation_details,
            "test_class_names": [c["class_name"] for c in all_test_classes],
        }
        Path(args.output_json).write_text(
            json.dumps(output_data, indent=2, ensure_ascii=False), encoding="utf-8"
        )
        print(f"\nResults saved to: {args.output_json}")

    # Cleanup
    if text_log_fh:
        text_log_fh.close()

    elapsed = perf_counter() - start_time
    print(f"Total elapsed time: {format_elapsed_time(elapsed)}")

    return 0 if covered > 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
