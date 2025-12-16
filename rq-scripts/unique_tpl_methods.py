import json
import os

def load_entrypoint_paths(path):
    """Load JSON file containing { 'entryPointPaths': [ ... ] }"""
    try:
        with open(path, "r") as f:
            data = json.load(f)

        if not isinstance(data, dict):
            print(f"Warning: {path} is not a dict.")
            return []

        entry_paths = data.get("entryPointPaths")
        if not isinstance(entry_paths, list):
            print(f"Warning: {path} missing 'entryPointPaths' list.")
            return []

        # keep only dicts
        return [item for item in entry_paths if isinstance(item, dict)]

    except Exception as e:
        print(f"Error reading {path}: {e}")
        return []


def count_unique_pairs(records):
    pairs = set()
    for record in records:
        path = record.get("path", [])
        third = record.get("thirdPartyMethod")

        if path and third:
            pairs.add((path[-1], third))
    return pairs


def count_unique_methods(records):
    return {record["thirdPartyMethod"]
            for record in records
            if "thirdPartyMethod" in record}


def main():
    root_folder = "examples"

    for entry in os.listdir(root_folder):
        subfolder = os.path.join(root_folder, entry)

        # only subdirectories
        if not os.path.isdir(subfolder):
            continue

        json_path = os.path.join(subfolder, "third_party_apis_entry_point.json")

        # only process if file exists
        if not os.path.isfile(json_path):
            continue

        print(f"\nProcessing file: {json_path}")

        # load data for just this file
        records = load_entrypoint_paths(json_path)

        # compute counts
        unique_pairs = count_unique_pairs(records)
        unique_methods = count_unique_methods(records)

        print(f"  Unique (pathEnd, thirdPartyMethod) pairs: {len(unique_pairs)}")
        print(f"  Unique thirdPartyMethods: {len(unique_methods)}")


if __name__ == "__main__":
    main()
