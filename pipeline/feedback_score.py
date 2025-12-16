#!/usr/bin/env python3
"""
JaCoCo Coverage Checker for Third-Party Method Calls

This script:
1. Runs a specific test class and generates JaCoCo coverage report
2. Parses the JaCoCo HTML report
3. Checks if a third-party method is covered by the test

Usage:
    python jacoco_coverage_checker.py --project-dir /path/to/project \
                                       --test-class com.example.MyTest \
                                       --method-class com.example.MyClass \
                                       --target-method org.thirdparty.Library.someMethod
"""

import argparse
import subprocess
import sys
from pathlib import Path
from html.parser import HTMLParser
import logging

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


class JaCoCoHTMLParser(HTMLParser):
    """Custom HTML parser to extract covered code lines from JaCoCo reports"""

    def __init__(self):
        super().__init__()
        self.covered_lines = set()
        self.in_covered_span = False
        self.current_text = []

    def handle_starttag(self, tag, attrs):
        if tag == 'span':
            attr_dict = dict(attrs)
            # Check if this span has an id starting with 'L' and class contains 'fc'
            span_id = attr_dict.get('id', '')
            span_class = attr_dict.get('class', '')

            if span_id.startswith('L') and 'fc' in span_class:
                self.in_covered_span = True
                self.current_text = []

    def handle_endtag(self, tag):
        if tag == 'span' and self.in_covered_span:
            code_line = ''.join(self.current_text).strip()
            if code_line:
                self.covered_lines.add(code_line)
            self.in_covered_span = False
            self.current_text = []

    def handle_data(self, data):
        if self.in_covered_span:
            self.current_text.append(data)


class JaCoCoRunner:
    """Handles running tests and generating JaCoCo reports"""

    def __init__(self, project_dir):
        self.project_dir = Path(project_dir)
        if not self.project_dir.exists():
            raise ValueError(f"Project directory does not exist: {project_dir}")

    def run_test_with_coverage(self, test_class):
        """
        Run a specific test class and generate JaCoCo coverage report.

        Args:
            test_class: Fully qualified test class name (e.g., com.example.MyTest)

        Returns:
            Path to the generated JaCoCo HTML report directory
        """
        logger.info(f"Running test class: {test_class}")

        # Clean previous reports
        clean_cmd = ["mvn", "clean", "-f", str(self.project_dir / "pom.xml")]
        try:
            result = subprocess.run(clean_cmd, check=True, capture_output=True, text=True)
            logger.info("Cleaned previous build artifacts")
            # Print Maven clean output
            print("\n" + "="*80)
            print("MAVEN CLEAN OUTPUT")
            print("="*80)
            if result.stdout:
                print(result.stdout)
            if result.stderr:
                print(result.stderr)
        except subprocess.CalledProcessError as e:
            logger.warning(f"Clean failed: {e.stderr}")
            print("\n" + "="*80)
            print("MAVEN CLEAN FAILED")
            print("="*80)
            if e.stdout:
                print(e.stdout)
            if e.stderr:
                print(e.stderr)

        # Run test with JaCoCo
        test_cmd = [
            "mvn",
            "test",
            f"-Dtest={test_class}",
            "jacoco:report",
            "-Dcheckstyle.skip=true",
            "-f", str(self.project_dir / "pom.xml")
        ]

        try:
            result = subprocess.run(test_cmd, check=True, capture_output=True, text=True)
            logger.info("Test execution and JaCoCo report generation completed")
            
            # Print Maven test output
            print("\n" + "="*80)
            print("MAVEN TEST EXECUTION OUTPUT")
            print("="*80)
            if result.stdout:
                print(result.stdout)
            if result.stderr:
                print(result.stderr)
            print("="*80 + "\n")
            
        except subprocess.CalledProcessError as e:
            logger.error(f"Test execution failed")
            
            # Print Maven test failure output
            print("\n" + "="*80)
            print("MAVEN TEST EXECUTION FAILED")
            print("="*80)
            if e.stdout:
                print(e.stdout)
            if e.stderr:
                print(e.stderr)
            print("="*80 + "\n")
            
            raise RuntimeError(f"Failed to run test: {e.stderr}")

        # Return path to JaCoCo HTML report
        jacoco_html_dir = self.project_dir / "target" / "site" / "jacoco"
        if not jacoco_html_dir.exists():
            raise RuntimeError(f"JaCoCo HTML report not found at: {jacoco_html_dir}")

        logger.info(f"JaCoCo report generated at: {jacoco_html_dir}")
        return jacoco_html_dir


class CoverageChecker:
    """Parses JaCoCo HTML reports and checks method coverage"""

    def __init__(self):
        self.coverage_cache = {}  # Cache for covered methods per HTML file

    def is_method_covered(self, method_class, target_method, jacoco_html_dir):
        """
        Check if a target third-party method is covered by tests.

        Args:
            method_class: Fully qualified name of the class being tested (e.g., com.example.MyClass)
            target_method: Fully qualified third-party method (e.g., org.library.Class.method)
            jacoco_html_dir: Path to JaCoCo HTML report directory

        Returns:
            bool: True if the target method is covered, False otherwise
        """
        # Parse class information
        package_name = method_class.rsplit('.', 1)[0]
        simple_class_name = method_class.rsplit('.', 1)[1]

        # Build path to HTML file - JaCoCo uses dots in directory names, not slashes
        html_file = Path(jacoco_html_dir) / package_name / f"{simple_class_name}.java.html"

        if not html_file.exists():
            logger.warning(f"HTML file not found: {html_file}")
            return False

        logger.info(f"Parsing coverage report: {html_file}")

        # Parse target method information
        target_class = target_method.rsplit('.', 1)[0]
        target_short_class = target_class.rsplit('.', 1)[1] if '.' in target_class else target_class
        target_method_name = target_method.rsplit('.', 1)[1]

        # Check if already cached
        html_path = str(html_file)
        if html_path in self.coverage_cache:
            covered_lines = self.coverage_cache[html_path]
        else:
            # Parse HTML and extract covered lines
            covered_lines = self._parse_covered_lines(html_file)
            self.coverage_cache[html_path] = covered_lines
            logger.info(f"Cached {len(covered_lines)} covered code lines")

        # Check if target method is in covered lines
        is_covered = self._check_method_in_lines(
            covered_lines,
            target_short_class,
            target_method_name
        )

        if is_covered:
            logger.info(f"✓ Third-party method '{target_method}' IS covered by tests")
        else:
            logger.info(f"✗ Third-party method '{target_method}' IS NOT covered by tests")

        return is_covered

    def _parse_covered_lines(self, html_file):
        """
        Parse JaCoCo HTML file and extract all covered code lines.

        Returns:
            set: Set of covered code lines (text content)
        """
        with open(html_file, 'r', encoding='utf-8') as f:
            html_content = f.read()

        parser = JaCoCoHTMLParser()
        parser.feed(html_content)

        return parser.covered_lines

    def _check_method_in_lines(self, covered_lines, target_class, method_name):
        """
        Check if a specific method appears in the covered lines.

        Args:
            covered_lines: Set of covered code lines
            target_class: Short class name of the target
            method_name: Method name to search for

        Returns:
            bool: True if method is found in covered lines
        """
        # Handle special cases
        if method_name == "<init>":
            # Constructor - look for "new ClassName("
            pattern = f"new {target_class}("
            for line in covered_lines:
                if pattern in line:
                    logger.debug(f"Found constructor call: {line}")
                    return True

        elif method_name == "<clinit>":
            # Static initializer - look for class name usage
            for line in covered_lines:
                if target_class in line:
                    logger.debug(f"Found static initializer usage: {line}")
                    return True

        else:
            # Regular method - look for method name
            for line in covered_lines:
                if method_name in line:
                    logger.debug(f"Found method call: {line}")
                    return True

        return False


def main():
    parser = argparse.ArgumentParser(
        description='Check if a third-party method is covered by a specific test class using JaCoCo'
    )
    parser.add_argument(
        '--project-dir',
        required=True,
        help='Path to the Maven project directory'
    )
    parser.add_argument(
        '--test-class',
        required=True,
        help='Fully qualified test class name (e.g., com.example.MyTest)'
    )
    parser.add_argument(
        '--method-class',
        required=True,
        help='Fully qualified class name containing the method (e.g., com.example.MyClass)'
    )
    parser.add_argument(
        '--target-method',
        required=True,
        help='Fully qualified third-party method to check (e.g., org.library.Class.method)'
    )
    parser.add_argument(
        '--debug',
        action='store_true',
        help='Enable debug logging'
    )

    args = parser.parse_args()

    if args.debug:
        logger.setLevel(logging.DEBUG)

    try:
        # Step 1: Run test and generate JaCoCo report
        runner = JaCoCoRunner(args.project_dir)
        jacoco_html_dir = runner.run_test_with_coverage(args.test_class)

        # Step 2 & 3: Check if target method is covered
        checker = CoverageChecker()
        is_covered = checker.is_method_covered(
            args.method_class,
            args.target_method,
            jacoco_html_dir
        )

        # Step 4: Return result
        if is_covered:
            print(f"\n✓ SUCCESS: Third-party method '{args.target_method}' is covered by test '{args.test_class}'")
            sys.exit(0)
        else:
            print(f"\n✗ FAILURE: Third-party method '{args.target_method}' is NOT covered by test '{args.test_class}'")
            sys.exit(1)

    except Exception as e:
        logger.error(f"Error: {e}", exc_info=args.debug)
        sys.exit(2)


if __name__ == "__main__":
    main()
    