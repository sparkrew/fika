#!/usr/bin/env python3
"""
Scan first-level subdirectories of an 'examples' directory, find 'path-stats.json'
in each subdirectory, count occurrences of "shortestPathLength" and write a
path_length.json with the per-project counts.

Usage:
    python count_path_lengths.py                 # uses ./examples and writes ./examples/path_length.json
    python count_path_lengths.py --examples DIR  # specify examples dir
    python count_path_lengths.py --output FILE   # specify output file
"""

import os
import json
import argparse
from collections import Counter

def process_project(project_path):
    """
    Look for path-stats.json inside project_path. If found and is valid JSON list,
    count the 'shortestPathLength' values and return a Counter.
    """
    stats_file = os.path.join(project_path, "path-stats.json")
    if not os.path.isfile(stats_file):
        return None

    try:
        with open(stats_file, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        print(f"Warning: failed to load JSON from {stats_file}: {e}")
        return None

    # Data is expected to be a list of records. If it's a dict, try to handle gracefully.
    records = data if isinstance(data, list) else [data]

    counter = Counter()
    for rec in records:
        if not isinstance(rec, dict):
            continue
        # Accept numeric or string values for shortestPathLength
        val = rec.get("shortestPathLength")
        if val is None:
            continue
        try:
            # convert to int for consistent counting
            length = int(val)
        except Exception:
            # skip non-int convertible values
            continue
        counter[length] += 1

    return counter

def main(examples_dir, output_file):
    if not os.path.isdir(examples_dir):
        print(f"Error: examples directory not found: {examples_dir}")
        return 1

    result = {}
    # iterate only first-level entries and only directories
    with os.scandir(examples_dir) as it:
        for entry in it:
            if not entry.is_dir():
                # ignore files in examples root
                continue
            project_name = entry.name
            project_path = entry.path
            counts = process_project(project_path)
            if counts is None:
                # no path-stats.json or failed to parse; skip but note empty dict
                print(f"Skipping {project_name}: no valid path-stats.json found")
                continue
            # convert keys to strings for JSON compatibility (optional)
            # If you prefer numeric keys, remove the str() conversion.
            result[project_name] = {str(k): v for k, v in sorted(counts.items())}
            print(f"Processed {project_name}: {sum(counts.values())} records, {len(counts)} distinct lengths")

    # Write output
    out_dir = os.path.dirname(output_file) or "."
    if out_dir and not os.path.exists(out_dir):
        os.makedirs(out_dir, exist_ok=True)

    try:
        with open(output_file, "w", encoding="utf-8") as f:
            json.dump(result, f, indent=2, ensure_ascii=False)
        print(f"Wrote aggregated results to {output_file}")
    except Exception as e:
        print(f"Error writing output file {output_file}: {e}")
        return 1

    return 0

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Aggregate shortestPathLength counts from path-stats.json files")
    parser.add_argument("--examples", "-e", default="examples",
                        help="Path to the parent 'examples' directory (default: ./examples)")
    parser.add_argument("--output", "-o", default=os.path.join("examples", "path_length.json"),
                        help="Output JSON file (default: ./examples/path_length.json)")
    args = parser.parse_args()
    raise SystemExit(main(args.examples, args.output))
