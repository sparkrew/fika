import json
import re
import sys
from collections import defaultdict


LOG_FILE_PATH = "/all_pipeline_logs/module/module.log"
JSON_FILE_PATH = "/third_party_apis_full_methods.json"
OUTPUT_FILE_PATH = "/path_length.txt"


def parse_log_for_successes(log_path):
    """
    Parse the log file and return a list of 0-based record indices
    where a test was approved (i.e., '✓ Test approved at iteration' was found).
    
    Log uses 1-based test case numbering: "PROCESSING TEST CASE X/647"
    We convert to 0-based index for JSON lookup: record_index = X - 1
    """
    successes = []  # list of (record_index, test_case_number)

    current_test_case = None

    with open(log_path, "r", encoding="utf-8") as f:
        for line in f:
            # Detect start of a new test case block
            match_case = re.search(r"PROCESSING TEST CASE (\d+)/\d+", line)
            if match_case:
                current_test_case = int(match_case.group(1))
            
            # Detect success marker
            if "✓ Test approved at iteration" in line:
                if current_test_case is not None:
                    record_index = current_test_case - 1  # convert to 0-based
                    successes.append((record_index, current_test_case))
                else:
                    print("WARNING: Found '✓ Test approved at iteration' but no current test case number was set.")

    return successes


def main():
    # Load log 
    print(f"Reading log file: {LOG_FILE_PATH}")
    successes = parse_log_for_successes(LOG_FILE_PATH)
    print(f"Found {len(successes)} successful test case(s).")

    #  Load JSON 
    print(f"Reading JSON file: {JSON_FILE_PATH}")
    with open(JSON_FILE_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)

    records = data.get("fullMethodsPaths", [])
    total_records = len(records)
    print(f"Total records in JSON: {total_records}")

    #  Map successes to JSON records and collect path lengths 
    path_length_counts = defaultdict(int)
    not_covered_records = []

    for record_index, test_case_number in successes:
        if record_index >= total_records:
            print(f"WARNING: Test case {test_case_number} maps to record index {record_index}, "
                  f"but JSON only has {total_records} records. Skipping.")
            continue

        record = records[record_index]
        path = record.get("path", [])
        path_length = len(path)
        path_length_counts[path_length] += 1

        covered = record.get("covered", None)
        if covered is not True:
            not_covered_records.append({
                "test_case_number": test_case_number,
                "record_index": record_index,
                "entryPoint": record.get("entryPoint", "N/A"),
                "thirdPartyMethod": record.get("thirdPartyMethod", "N/A"),
                "covered": covered,
                "path_length": path_length,
            })

    #  Warn about records marked as not covered 
    if not_covered_records:
        print("\n" + "=" * 70)
        print(f"WARNING: {len(not_covered_records)} successful test case(s) have 'covered': false (or missing) in the JSON:")
        print("=" * 70)
        for item in not_covered_records:
            print(f"  [WARN] Test case #{item['test_case_number']} -> JSON record index {item['record_index']}")
            print(f"         entryPoint     : {item['entryPoint']}")
            print(f"         thirdPartyMethod: {item['thirdPartyMethod']}")
            print(f"         covered        : {item['covered']}")
            print(f"         path_length    : {item['path_length']}")
            print()
    else:
        print("\nAll successful test cases have 'covered': true in the JSON. No warnings.")

    #  Write path_length.txt 
    print(f"\nWriting path length counts to: {OUTPUT_FILE_PATH}")
    with open(OUTPUT_FILE_PATH, "w", encoding="utf-8") as out:
        out.write("Path Length Distribution (successful tests only)\n")
        out.write("=" * 45 + "\n")
        if path_length_counts:
            for length in sorted(path_length_counts.keys()):
                line = f"Path length {length} - count: {path_length_counts[length]}"
                print(f"  {line}")
                out.write(line + "\n")
        else:
            out.write("No successful test cases found.\n")
            print("  No successful test cases found.")

    total_successful = sum(path_length_counts.values())
    summary = f"\nTotal successful test cases mapped: {total_successful}"
    print(summary)
    with open(OUTPUT_FILE_PATH, "a", encoding="utf-8") as out:
        out.write(summary + "\n")

    print(f"\nDone. Results written to '{OUTPUT_FILE_PATH}'.")


if __name__ == "__main__":
    main()
