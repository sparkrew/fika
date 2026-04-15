import os
import subprocess

def count_compile_direct_dependencies(file_path):
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
            if line.startswith("org.apache.poi"):
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

            scope = parts[-1].strip()
            if "(" in scope:
                scope = scope.split("(")[0].strip()

            if scope in {"compile", "provided"}:
                count += 1

    return 0 if none_detected else count


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

    subprocess.run(
        cmd,
        cwd=project_dir,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL
    )

    return output_file


def process_single_project(project_path):
    if not os.path.isdir(project_path):
        raise ValueError(f"Invalid project directory: {project_path}")

    target_dir = project_path

    output_file = run_dependency_scan(target_dir)
    return count_compile_direct_dependencies(output_file)


if __name__ == "__main__":
    PROJECT_DIR = "/Users/yogyagamage/Documents/UdeM/Courses/Testing/project/eval/poi-tl/poi-tl"
    count = process_single_project(PROJECT_DIR)
    print(f"{os.path.basename(PROJECT_DIR)}: {count} compile/provided direct dependencies")
