import os
import subprocess

BASE_DIR = "/Users/yogyagamage/Documents/UdeM/Courses/Testing/project/eval"

SUBMODULE_MAP = {
    "pdfbox": "pdfbox",
    "poi-tl": "poi-tl",
    "immutables": "gson",
    "jimfs": "jmifs",
    "jooby": "jooby",
    "modelmapper": "core",
    "flink": "flink-core",
    "graphhopper": "core",
    "guice": "core",
    "helidon": "openapi",
    "httpcomponents-client": "httpclient5",
    "OpenPDF": "openpdf-core",
    "pf4j": "pf4j",
    "scribejava": "scribejava-core",
    "tablesaw": "json",
    "tika": "tika-core",
    "undertow": "core"
}


def count_compile_direct_dependencies(file_path):
    """
    Count only compile-scoped direct dependencies, including optional ones.
    """
    if not os.path.isfile(file_path):
        return 0

    count = 0
    none_detected = False

    with open(file_path, "r") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("The following files have been resolved"):
                continue
            if line.lower() == "none":
                none_detected = True
                continue
            if "-- module" not in line:
                continue

            left_side = line.split("-- module")[0].strip()
            parts = left_side.split(":")
            if len(parts) < 5:
                continue

            scope = parts[-1].strip()  # e.g., "compile" or "compile (optional)"
            # remove any parenthetical text
            if "(" in scope:
                scope = scope.split("(")[0].strip()

            if scope == "compile":
                count += 1
            
            if scope == "provided":
                count += 1

    if none_detected:
        return 0
    return count



def run_dependency_scan(project_dir):
    output_file = os.path.join(project_dir, "direct-dependencies.txt")

    cmd = [
        "mvn",
        "dependency:list",
        "-DexcludeTransitive=true",
        f"-DoutputFile={output_file}",
        "-DoutputAbsoluteArtifactFilename=false",
        "-B"
    ]

    try:
        subprocess.run(cmd, cwd=project_dir, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception as e:
        print(f"Error running Maven in {project_dir}: {e}")

    return output_file


def process_projects(base_dir):
    results = {}

    for item in os.listdir(base_dir):
        project_path = os.path.join(base_dir, item)

        if not os.path.isdir(project_path):
            continue

        project_name = item  # folder name

        # Determine if project has a special submodule
        if project_name in SUBMODULE_MAP:
            sub = SUBMODULE_MAP[project_name]
            sub_path = os.path.join(project_path, sub)
            if os.path.isdir(sub_path):
                target_dir = sub_path
            else:
                target_dir = project_path  # fallback
        else:
            target_dir = project_path  # not a multi-module project

        output_file = run_dependency_scan(target_dir)
        count = count_compile_direct_dependencies(output_file)

        results[project_name] = count

    return results


if __name__ == "__main__":
    results = process_projects(BASE_DIR)
    for proj, count in results.items():
        print(f"{proj}: {count} compile-provided-scoped direct dependencies")
