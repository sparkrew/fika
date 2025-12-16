import os
from bs4 import BeautifulSoup

# Multi-module project → submodule name mapping
SUBMODULES = {
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
    "undertow": "core",
}

def extract_line_coverage(index_path):
    """Extract line coverage percentage from a JaCoCo index.html file."""
    if not os.path.isfile(index_path):
        print(f"index.html not found: {index_path}")
        return None

    with open(index_path, "r") as f:
        soup = BeautifulSoup(f, "html.parser")

    total_row = soup.select_one("tfoot tr")
    if not total_row:
        print(f"No <tfoot><tr> found: {index_path}")
        return None

    cells = total_row.find_all("td")

    try:
        # Missed Lines = 8th <td> (index 7)
        # Total Lines  = 9th <td> (index 8)
        missed_lines = int(cells[7].text.replace(",", "").strip())
        total_lines = int(cells[8].text.replace(",", "").strip())
    except Exception:
        print("Failed parsing:", [c.text for c in cells])
        return None

    if total_lines == 0:
        return 0.0

    return (1 - missed_lines / total_lines) * 100


def get_coverage_for_all_projects(root_folder):
    results = {}

    for project in os.listdir(root_folder):
        project_path = os.path.join(root_folder, project)
        if not os.path.isdir(project_path):
            continue

        print(f"Processing project: {project}")

        # Determine submodule path or root path
        if project in SUBMODULES:
            sub = SUBMODULES[project]
            index_path = os.path.join(
                project_path, sub, "target", "site", "jacoco", "index.html"
            )
        else:
            # Single-module project
            index_path = os.path.join(
                project_path, "target", "site", "jacoco", "index.html"
            )

        coverage = extract_line_coverage(index_path)
        results[project] = coverage

    return results



if __name__ == "__main__":
    root = "/Users/yogyagamage/Documents/UdeM/Courses/Testing/project/eval"

    coverage_results = get_coverage_for_all_projects(root)

    for proj, cov in coverage_results.items():
        print(f"{proj}: {cov}")
