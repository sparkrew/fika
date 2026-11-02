from __future__ import annotations

from pathlib import Path
from typing import List, Tuple


def extract_java_sources(
    project_root: Path,
    exclude_tests: bool = True,
) -> Tuple[str, List[str]]:
    """
    Walk a Maven/Gradle Java project and collect all .java source files.

    Args:
        project_root: Root directory of the Java project.
        exclude_tests: If True, skip files under any ``src/test`` subtree.

    Returns:
        A tuple of (formatted_source_block, file_paths).
        *formatted_source_block* is a single string with every file delimited by
        a ``// FILE: <relative-path>`` header, ready to embed in an LLM prompt.
        *file_paths* is the list of relative paths that were included.
    """
    project_root = project_root.resolve()
    java_files: List[Path] = sorted(project_root.rglob("*.java"))

    parts: List[str] = []
    included_paths: List[str] = []

    for java_file in java_files:
        rel = java_file.relative_to(project_root)
        rel_str = str(rel)

        if exclude_tests and "src/test" in rel_str:
            continue

        try:
            content = java_file.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue

        parts.append(f"// FILE: {rel_str}\n{content}")
        included_paths.append(rel_str)

    formatted = "\n\n".join(parts)
    return formatted, included_paths
