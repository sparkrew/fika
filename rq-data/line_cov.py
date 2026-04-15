import xml.etree.ElementTree as ET

def get_line_coverage_percentage(jacoco_xml_path):
    tree = ET.parse(jacoco_xml_path)
    root = tree.getroot()

    for counter in root.findall("counter"):
        if counter.attrib.get("type") == "LINE":
            covered = int(counter.attrib["covered"])
            missed = int(counter.attrib["missed"])
            total = covered + missed
            return round((covered / total) * 100, 2) if total else 0.0

    return 0.0


if __name__ == "__main__":
    jacoco_report = "target/site/jacoco/jacoco.xml"
    coverage = get_line_coverage_percentage(jacoco_report)
    print(f"Line coverage: {coverage}%")
