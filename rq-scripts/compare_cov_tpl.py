import json
import os

def strip_params(method):
    """Remove parameters: keep only 'a.b.Class.method'."""
    if not method:
        return method
    return method.split("(")[0]


def load_third_party_methods(path):
    """Load third_party_apis_entry_point.json → return set of method names without params."""
    try:
        with open(path, "r") as f:
            data = json.load(f)

        entries = data.get("entryPointPaths", [])
        methods = set()

        for entry in entries:
            m = entry.get("thirdPartyMethod")
            if m:
                methods.add(strip_params(m))

        return methods

    except Exception as e:
        print(f"Error reading {path}: {e}")
        return set()


def load_coverage_methods(path):
    """Load coverage.json → return ONLY FALSE methods (without params)."""
    try:
        with open(path, "r") as f:
            data = json.load(f)

        methods = set()
        for entry in data:
            if entry.get("covered") is False:   # ← Only false ones
                m = entry.get("method")
                if m:
                    methods.add(strip_params(m))

        return methods

    except Exception as e:
        print(f"Error reading {path}: {e}")
        return set()


def main():
    root = "examples"
    diff_output = []

    for entry in os.listdir(root):
        subfolder = os.path.join(root, entry)
        if not os.path.isdir(subfolder):
            continue

        third_party_path = os.path.join(subfolder, "third_party_apis_entry_point.json")
        coverage_path    = os.path.join(subfolder, "coverage.json")

        if not (os.path.isfile(third_party_path) and os.path.isfile(coverage_path)):
            continue

        # Load only needed data
        tp_methods  = load_third_party_methods(third_party_path)
        cov_methods = load_coverage_methods(coverage_path)   # only false ones

        # Differences
        only_in_tp  = tp_methods - cov_methods
        only_in_cov = cov_methods - tp_methods
        in_both     = tp_methods & cov_methods

        diff_output.append(f"===== DIFF FOR {entry} =====\n")

        diff_output.append("Methods only in third_party_apis_entry_point.json:\n")
        diff_output.extend(f"  + {m}\n" for m in sorted(only_in_tp)) if only_in_tp else diff_output.append("  (none)\n")

        diff_output.append("\nMethods only in coverage.json (FALSE covered methods):\n")
        diff_output.extend(f"  - {m}\n" for m in sorted(only_in_cov)) if only_in_cov else diff_output.append("  (none)\n")

        diff_output.append("\nMethods present in both:\n")
        diff_output.extend(f"  = {m}\n" for m in sorted(in_both)) if in_both else diff_output.append("  (none)\n")

        diff_output.append("\n\n")

    with open("diff.txt", "w") as f:
        f.writelines(diff_output)

    print("diff.txt created successfully.")


if __name__ == "__main__":
    main()
