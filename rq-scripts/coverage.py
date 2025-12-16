import json
import os

def strip_parameters(method_name):
    """Return method name before the '('."""
    if '(' in method_name:
        return method_name.split('(')[0]
    return method_name


def analyze_coverage(path):
    """Return counts:
       (true_with_params, false_with_params, total_with_params,
        true_without_params, false_without_params, total_without_params)
    """
    try:
        with open(path, "r") as f:
            data = json.load(f)

        if not isinstance(data, list):
            return (0, 0, 0, 0, 0, 0)

        # Using sets so uniqueness is guaranteed
        true_with = set()
        false_with = set()

        true_without = set()
        false_without = set()

        for entry in data:
            if not isinstance(entry, dict):
                continue

            method = entry.get("method")
            covered = entry.get("covered")

            if not method or covered not in [True, False]:
                continue

            method_no_params = strip_parameters(method)

            # considering parameters
            if covered is True:
                true_with.add(method)
            else:
                false_with.add(method)

            # ignoring parameters
            if covered is True:
                true_without.add(method_no_params)
            else:
                false_without.add(method_no_params)

        return (
            len(true_with),
            len(false_with),
            len(true_with) + len(false_with),

            len(true_without),
            len(false_without),
            len(true_without) + len(false_without),
        )

    except Exception as e:
        print(f"Error reading {path}: {e}")
        return (0, 0, 0, 0, 0, 0)


def main():
    root = "examples"

    for entry in os.listdir(root):
        subfolder = os.path.join(root, entry)
        if not os.path.isdir(subfolder):
            continue

        coverage_path = os.path.join(subfolder, "coverage.json")
        if not os.path.isfile(coverage_path):
            continue

        print(f"\nProcessing: {coverage_path}")

        (
            t_with, f_with, total_with,
            t_without, f_without, total_without
        ) = analyze_coverage(coverage_path)

        print(f"  Considering params:")
        print(f"    true  = {t_with}")
        print(f"    false = {f_with}")
        print(f"    total = {total_with}")

        print(f"  Ignoring params:")
        print(f"    true  = {t_without}")
        print(f"    false = {f_without}")
        print(f"    total = {total_without}")


if __name__ == "__main__":
    main()
