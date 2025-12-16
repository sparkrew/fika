import os
import json
from pathlib import Path
import subprocess

# Base eval folder
BASE_DIR = "/Users/yogyagamage/Documents/UdeM/Courses/Testing/project/eval"

# Map of project -> submodule (unchanged)
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

# Folder where PublicMethodCounter.class is located (folder, not .class file)
PUBLIC_METHOD_COUNTER_DIR = "/Users/yogyagamage/Documents/UdeM/Courses/Testing/project/fika/rq-scripts"

# Full SootUp classpath - ensure this includes all SootUp & required jars (update if needed)
SOOTUP_CLASSPATH = ":".join([
    "/Users/yogyagamage/.m2/repository/org/soot-oss/sootup.core/2.0.0/sootup.core-2.0.0.jar",
    "/Users/yogyagamage/.m2/repository/org/soot-oss/sootup.java.core/2.0.0/sootup.java.core-2.0.0.jar",
    "/Users/yogyagamage/.m2/repository/org/soot-oss/sootup.java.bytecode.frontend/2.0.0/sootup.java.bytecode.frontend-2.0.0.jar",
    "/Users/yogyagamage/.m2/repository/org/soot-oss/sootup.interceptors/2.0.0/sootup.interceptors-2.0.0.jar",
    "/Users/yogyagamage/.m2/repository/org/soot-oss/sootup.analysis.intraprocedural/2.0.0/sootup.analysis.intraprocedural-2.0.0.jar",
    "/Users/yogyagamage/.m2/repository/org/soot-oss/sootup.callgraph/2.0.0/sootup.callgraph-2.0.0.jar",
    "/Users/yogyagamage/.m2/repository/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar",
    "/Users/yogyagamage/.m2/repository/org/ow2/asm/asm-util/9.7.1/asm-util-9.7.1.jar",
    "/Users/yogyagamage/.m2/repository/org/ow2/asm/asm-tree/9.7.1/asm-tree-9.7.1.jar",
    "/Users/yogyagamage/.m2/repository/org/ow2/asm/asm-analysis/9.7.1/asm-analysis-9.7.1.jar",
    "/Users/yogyagamage/.m2/repository/org/ow2/asm/asm-commons/9.7.1/asm-commons-9.7.1.jar",
    "/Users/yogyagamage/.m2/repository/org/jgrapht/jgrapht-core/1.3.1/jgrapht-core-1.3.1.jar",
    "/Users/yogyagamage/.m2/repository/org/jheaps/jheaps/0.10/jheaps-0.10.jar",
    "/Users/yogyagamage/.m2/repository/com/google/guava/guava/33.3.0-jre/guava-33.3.0-jre.jar",
    "/Users/yogyagamage/.m2/repository/com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar",
    "/Users/yogyagamage/.m2/repository/com/google/guava/listenablefuture/9999.0-empty-to-avoid-conflict-with-guava/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar",
    "/Users/yogyagamage/.m2/repository/org/checkerframework/checker-qual/3.43.0/checker-qual-3.43.0.jar",
    "/Users/yogyagamage/.m2/repository/com/google/error_prone/error_prone_annotations/2.28.0/error_prone_annotations-2.28.0.jar",
    "/Users/yogyagamage/.m2/repository/com/google/j2objc/j2objc-annotations/3.0.0/j2objc-annotations-3.0.0.jar",
    "/Users/yogyagamage/.m2/repository/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar",
    "/Users/yogyagamage/.m2/repository/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar",
    "/Users/yogyagamage/.m2/repository/commons-io/commons-io/2.16.1/commons-io-2.16.1.jar",
    "/Users/yogyagamage/.m2/repository/com/google/code/gson/gson/2.8.9/gson-2.8.9.jar",
    "/Users/yogyagamage/.m2/repository/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar",
    "/Users/yogyagamage/.m2/repository/org/slf4j/slf4j-simple/2.0.13/slf4j-simple-2.0.13.jar"
])

# Java main class name (no .class)
JAVA_MAIN_CLASS = "PublicMethodCounter"

def compile_public_method_counter():
    """
    Compile PublicMethodCounter.java before running any analysis.
    Assumes .java file is located in PUBLIC_METHOD_COUNTER_DIR.
    """
    java_file = Path(PUBLIC_METHOD_COUNTER_DIR) / "PublicMethodCounter.java"

    if not java_file.exists():
        raise FileNotFoundError(f"{java_file} not found! Cannot compile.")

    print("Compiling PublicMethodCounter.java...")

    cmd = [
        "javac",
        "-cp",
        SOOTUP_CLASSPATH,  # full sootup + asm classpath
        str(java_file)
    ]

    # run compiler
    result = subprocess.run(cmd, capture_output=True, text=True)

    if result.returncode != 0:
        print("Compilation failed:")
        print(result.stderr)
        raise RuntimeError("Failed to compile PublicMethodCounter.java")

    print("Compilation successful.")

def run_copy_dependencies(module_dir):
    """Run maven to copy compile-scope dependencies into target/dependency. Raise on failure."""
    print(f"Copying compile-scope dependencies for {module_dir} ...")
    cmd = [
        "mvn",
        "dependency:copy-dependencies",
        "-DincludeScope=compile",
        "-DoutputDirectory=target/dependency"
    ]
    try:
        proc = subprocess.run(cmd, cwd=module_dir, capture_output=True, text=True, check=True)
        # optionally print stdout for debugging:
        # print(proc.stdout)
    except subprocess.CalledProcessError as e:
        print(f"ERROR: mvn dependency:copy-dependencies failed for {module_dir}")
        print("----- stdout -----")
        print(e.stdout)
        print("----- stderr -----")
        print(e.stderr)
        raise


def count_public_methods_in_jar(jar_path: Path) -> int:
    """
    Calls the Java PublicMethodCounter (which now expects only the jar path) and returns the integer count.
    """
    cmd = [
        "java",
        "-cp",
        f"{PUBLIC_METHOD_COUNTER_DIR}:{SOOTUP_CLASSPATH}",
        JAVA_MAIN_CLASS,
        str(jar_path)
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, check=True)
        out = proc.stdout.strip()
        if not out:
            print(f"Warning: Java counter produced no output for {jar_path}")
            return 0
        try:
            return int(out)
        except ValueError:
            print(f"Unexpected output from Java counter for {jar_path}: {out}")
            return 0
    except subprocess.CalledProcessError as e:
        print(f"Java counter failed for {jar_path}")
        print("----- stdout -----")
        print(e.stdout)
        print("----- stderr -----")
        print(e.stderr)
        return 0


def main():
    project_results = {}  # project -> {dep_name -> count, "num_dependencies": N, "total_methods": M}
    compile_public_method_counter()
    for project in sorted(os.listdir(BASE_DIR)):
        proj_path = Path(BASE_DIR) / project
        if not proj_path.is_dir():
            continue

        # determine module directory (use submodule mapping if present)
        submodule = SUBMODULE_MAP.get(project, "")
        module_dir = proj_path / submodule if submodule else proj_path
        if not module_dir.exists():
            module_dir = proj_path

        print(f"\nProcessing project {project} (module dir: {module_dir})")

        # copy dependencies
        # try:
        #     run_copy_dependencies(module_dir)
        # except Exception:
        #     print(f"Skipping {project} due to dependency copy failure.")
        #     continue

        # find jars
        dep_dir = module_dir / "target" / "dependency"
        if not dep_dir.exists():
            print(f"No dependency folder for {project} at {dep_dir}, skipping")
            continue

        jars = sorted(dep_dir.glob("*.jar"))
        if not jars:
            print(f"No JAR files found for {project} in {dep_dir}, skipping")
            continue

        project_results[project] = {}
        total_methods = 0
        num_deps = 0

        for jar in jars:
            jar_name = jar.name
            print(f"  Counting public methods in {jar_name} ...", end=" ", flush=True)
            count = count_public_methods_in_jar(jar)
            print(count)
            project_results[project][jar_name] = count
            total_methods += count
            num_deps += 1

        project_results[project]["num_dependencies"] = num_deps
        project_results[project]["total_methods"] = total_methods

    # Print final aggregated results
    print("\n=== FINAL SUMMARY ===")
    for project, data in project_results.items():
        print(f"\nProject: {project}")
        print(f"  Number of dependencies: {data.get('num_dependencies', 0)}")
        for jar_name, cnt in data.items():
            if jar_name in ("num_dependencies", "total_methods"):
                continue
            print(f"  {jar_name}: {cnt} public methods")
        print(f"  Total public methods from dependencies: {data.get('total_methods', 0)}")


if __name__ == "__main__":
    main()
