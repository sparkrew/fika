#!/usr/bin/env python3
"""
Test Coverage Orchestrator

This script orchestrates the execution of multiple tests with JaCoCo coverage checking:
1. Iterates through test folders
2. Extracts metadata from each test
3. Copies test files to the appropriate location in the Maven project
4. Runs the coverage checker script
5. Collects and records results
6. Cleans up copied test files

Usage:
    python test_coverage_orchestrator.py \
        --tests-dir /path/to/tests/folder \
        --project-dir /path/to/maven/project \
        --feedback-script /path/to/feedback_score.py \
        --output-json results.json \
        --log-file test_exec_log.txt
"""

import argparse
import json
import subprocess
import sys
import shutil
from pathlib import Path
from datetime import datetime
import logging

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


class TestOrchestrator:
    """Orchestrates test execution and coverage checking across multiple test cases"""

    def __init__(self, tests_dir, project_dir, feedback_script, output_json, log_file):
        self.tests_dir = Path(tests_dir)
        self.project_dir = Path(project_dir)
        self.feedback_script = Path(feedback_script)
        self.output_json = Path(output_json)
        self.log_file = Path(log_file)
        
        # Validate paths
        if not self.tests_dir.exists():
            raise ValueError(f"Tests directory does not exist: {tests_dir}")
        if not self.project_dir.exists():
            raise ValueError(f"Project directory does not exist: {project_dir}")
        if not self.feedback_script.exists():
            raise ValueError(f"Feedback script does not exist: {feedback_script}")
        
        self.results = []
        self.test_details = []  # New list for detailed test information

    def find_test_folders(self):
        """Find all subdirectories in the tests directory"""
        test_folders = [d for d in self.tests_dir.iterdir() if d.is_dir()]
        logger.info(f"Found {len(test_folders)} test folders")
        return test_folders

    def load_metadata(self, test_folder):
        """Load metadata.json from a test folder"""
        metadata_file = test_folder / "metadata.json"
        if not metadata_file.exists():
            logger.warning(f"No metadata.json found in {test_folder}")
            return None
        
        try:
            with open(metadata_file, 'r', encoding='utf-8') as f:
                metadata = json.load(f)
            logger.info(f"Loaded metadata from {test_folder.name}")
            return metadata
        except Exception as e:
            logger.error(f"Failed to load metadata from {test_folder}: {e}")
            return None

    def find_test_file(self, test_folder):
        """Find the Java test file in the folder"""
        java_files = list(test_folder.glob("*.java"))
        if not java_files:
            logger.warning(f"No Java test file found in {test_folder}")
            return None
        if len(java_files) > 1:
            logger.warning(f"Multiple Java files found in {test_folder}, using first one")
        return java_files[0]

    def parse_entry_point(self, entry_point):
        """
        Parse entry point to extract package, class, and method information
        
        Args:
            entry_point: e.g., "tech.tablesaw.io.jsonl.JsonlReader.read"
        
        Returns:
            dict with 'package', 'class_name', 'method', 'fully_qualified_class'
        """
        parts = entry_point.rsplit('.', 1)
        if len(parts) != 2:
            raise ValueError(f"Invalid entry point format: {entry_point}")
        
        fully_qualified_class = parts[0]
        method = parts[1]
        
        class_parts = fully_qualified_class.rsplit('.', 1)
        if len(class_parts) == 2:
            package = class_parts[0]
            class_name = class_parts[1]
        else:
            package = ""
            class_name = class_parts[0]
        
        return {
            'package': package,
            'class_name': class_name,
            'method': method,
            'fully_qualified_class': fully_qualified_class
        }

    def get_test_class_name(self, test_file):
        """Extract the fully qualified test class name from the test file"""
        import re
        
        # Read the file to find package declaration and class name
        with open(test_file, 'r', encoding='utf-8') as f:
            content = f.read()
        
        package = ""
        class_name = None
        
        # Find package declaration
        for line in content.split('\n'):
            line = line.strip()
            if line.startswith('package '):
                package = line.replace('package ', '').replace(';', '').strip()
                break
        
        # Find class name from class declaration
        # Matches: public class ClassName, class ClassName, public final class ClassName, etc.
        class_pattern = r'\b(?:public\s+)?(?:final\s+)?(?:abstract\s+)?class\s+(\w+)'
        class_match = re.search(class_pattern, content)
        
        if class_match:
            class_name = class_match.group(1)
        else:
            # Fallback to filename if class declaration not found
            class_name = test_file.stem
            logger.warning(f"Could not find class declaration in {test_file.name}, using filename: {class_name}")
        
        if package:
            return f"{package}.{class_name}"
        return class_name

    def get_test_destination_path(self, test_file, test_class_fqn):
        """
        Determine where to copy the test file in the Maven project
        
        Args:
            test_file: Path to the test file
            test_class_fqn: Fully qualified test class name (e.g., "io.jooby.EnvironmentTests" or just "JoobyTest")
        
        Returns:
            Path to the destination directory
        """
        # Standard Maven test directory structure
        test_base = self.project_dir / "src" / "test" / "java"
        
        if not test_base.exists():
            raise ValueError(f"Maven test directory not found: {test_base}")
        
        # If there's a package (contains a dot), use it for the path
        if '.' in test_class_fqn:
            package = test_class_fqn.rsplit('.', 1)[0]
            package_path = package.replace('.', '/')
            destination_dir = test_base / package_path
        else:
            # No package - put directly in the test/java directory
            destination_dir = test_base
        
        return destination_dir

    def copy_test_file(self, test_file, destination_dir):
        """Copy test file to the Maven project"""
        destination_dir.mkdir(parents=True, exist_ok=True)
        destination_file = destination_dir / test_file.name
        
        if destination_file.exists():
            logger.warning(f"Test file already exists at {destination_file}, will overwrite")
        
        shutil.copy2(test_file, destination_file)
        logger.info(f"Copied test file to {destination_file}")
        
        return destination_file

    def run_coverage_check(self, test_class, method_class, target_method):
        """
        Run the feedback_score.py script
        
        Returns:
            tuple: (exit_code, stdout, stderr)
        """
        cmd = [
            "python3",
            str(self.feedback_script),
            "--project-dir", str(self.project_dir),
            "--test-class", test_class,
            "--method-class", method_class,
            "--target-method", target_method
        ]
        
        logger.info(f"Running coverage check: {' '.join(cmd)}")
        
        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=600  # 10 minute timeout
            )
            
            return result.returncode, result.stdout, result.stderr
            
        except subprocess.TimeoutExpired:
            logger.error("Coverage check timed out after 10 minutes")
            return -1, "", "Timeout after 10 minutes"
        except Exception as e:
            logger.error(f"Failed to run coverage check: {e}")
            return -1, "", str(e)

    def parse_coverage_result(self, stdout, stderr, exit_code):
        """
        Parse the output to determine if target method was reached and detect failures
        
        Returns:
            dict with 'execution_successful', 'target_method_reached', and 'failure_type'
        """
        combined_output = stdout + "\n" + stderr
        
        execution_successful = False
        target_method_reached = False
        failure_type = None
        
        # Check for compilation errors
        if "COMPILATION ERROR" in combined_output or "[ERROR] COMPILATION ERROR" in combined_output:
            execution_successful = False
            failure_type = "compilation_failure"
            logger.warning("Detected compilation failure")
        
        # Check for test failures (assertion errors)
        elif "Tests run:" in combined_output and ("Failures:" in combined_output or "Errors:" in combined_output):
            # Parse test results to see if there were actual failures
            if self._has_test_failures(combined_output):
                execution_successful = False
                # Distinguish between assertion failures and runtime errors
                if "AssertionError" in combined_output or "junit.framework.AssertionFailedError" in combined_output:
                    failure_type = "assertion_failure"
                    logger.warning("Detected assertion failure")
                else:
                    failure_type = "test_runtime_error"
                    logger.warning("Detected test runtime error")
        
        # Check for other test runtime errors
        elif any(error in combined_output for error in [
            "Exception in thread",
            "java.lang.RuntimeException",
            "java.lang.NullPointerException",
            "BUILD FAILURE"
        ]) and not ("✓" in combined_output or "✗" in combined_output):
            execution_successful = False
            failure_type = "test_runtime_error"
            logger.warning("Detected test runtime error")
        
        # Check for successful execution with coverage results
        elif "✓ Third-party method" in combined_output and "IS covered by tests" in combined_output:
            execution_successful = True
            target_method_reached = True
        elif "✗ Third-party method" in combined_output and "IS NOT covered by tests" in combined_output:
            execution_successful = True
            target_method_reached = False
        elif "✓ SUCCESS: Third-party method" in combined_output:
            execution_successful = True
            target_method_reached = True
        elif "✗ FAILURE: Third-party method" in combined_output and "is NOT covered" in combined_output:
            execution_successful = True
            target_method_reached = False
        
        # If exit code is 2, it's a script error (not a test failure)
        if exit_code == 2 and not failure_type:
            execution_successful = False
            failure_type = "test_runtime_error"
        
        return {
            'execution_successful': execution_successful,
            'target_method_reached': target_method_reached,
            'failure_type': failure_type
        }
    
    def _has_test_failures(self, output):
        """
        Parse Maven test output to determine if there were actual test failures
        
        Looks for patterns like:
        Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
        """
        import re
        
        # Look for test result summary
        pattern = r'Tests run: (\d+),\s*Failures: (\d+),\s*Errors: (\d+)'
        matches = re.findall(pattern, output)
        
        for match in matches:
            tests_run, failures, errors = map(int, match)
            if failures > 0 or errors > 0:
                return True
        
        return False

    def log_execution(self, test_folder_name, stdout, stderr, exit_code):
        """Append execution log to the log file"""
        timestamp = datetime.now().isoformat()
        
        with open(self.log_file, 'a', encoding='utf-8') as f:
            f.write(f"\n{'='*80}\n")
            f.write(f"Test Folder: {test_folder_name}\n")
            f.write(f"Timestamp: {timestamp}\n")
            f.write(f"Exit Code: {exit_code}\n")
            f.write(f"{'='*80}\n\n")
            
            # The stdout now contains Maven output printed by feedback_score.py
            f.write("COMPLETE OUTPUT (includes Maven and Coverage Checker):\n")
            f.write("="*80 + "\n\n")
            
            if stdout:
                f.write("STDOUT:\n")
                f.write(stdout)
                f.write("\n\n")
            
            if stderr:
                f.write("STDERR:\n")
                f.write(stderr)
                f.write("\n\n")
            
            f.write(f"{'='*80}\n\n")
        
        logger.info(f"Logged execution results to {self.log_file}")

    def save_results(self):
        """Save all results to the output JSON file"""
        with open(self.output_json, 'w', encoding='utf-8') as f:
            json.dump(self.results, f, indent=2)
        logger.info(f"Saved results to {self.output_json}")
    
    def save_test_details(self):
        """Save detailed test information to a separate JSON file"""
        details_json = self.output_json.parent / f"{self.output_json.stem}_details.json"
        logger.info(f"Saving {len(self.test_details)} test details to {details_json}")
        with open(details_json, 'w', encoding='utf-8') as f:
            json.dump(self.test_details, f, indent=2)
        logger.info(f"Saved test details to {details_json}")
    
    def save_test_details(self):
        """Save detailed test information to a separate JSON file"""
        details_json = self.output_json.parent / f"{self.output_json.stem}_details.json"
        with open(details_json, 'w', encoding='utf-8') as f:
            json.dump(self.test_details, f, indent=2)
        logger.info(f"Saved test details to {details_json}")
    
    def extract_maven_output(self, stdout, stderr):
        """
        Extract only Maven output from the combined output
        
        Returns:
            str: Maven output only
        """
        combined = stdout + "\n" + stderr
        maven_lines = []
        
        # Look for Maven section markers
        in_maven_section = False
        lines = combined.split('\n')
        
        for line in lines:
            # Check if line is part of Maven output
            if any(marker in line for marker in [
                "MAVEN CLEAN OUTPUT",
                "MAVEN TEST EXECUTION OUTPUT",
                "MAVEN TEST EXECUTION FAILED",
                "MAVEN CLEAN FAILED",
                "="*80
            ]):
                if "MAVEN" in line:
                    in_maven_section = True
                elif "="*80 == line.strip() and in_maven_section:
                    # Check if next section is Maven or something else
                    continue
                maven_lines.append(line)
            elif in_maven_section:
                # Check if we're leaving Maven section
                if line.strip().startswith("20") and " - INFO - " in line:
                    # This is a logger line, we've left Maven section
                    in_maven_section = False
                elif "✓ SUCCESS:" in line or "✗ FAILURE:" in line:
                    in_maven_section = False
                else:
                    maven_lines.append(line)
        
        return '\n'.join(maven_lines).strip()

    def process_test_folder(self, test_folder):
        """Process a single test folder"""
        logger.info(f"\n{'='*80}")
        logger.info(f"Processing test folder: {test_folder.name}")
        logger.info(f"{'='*80}")
        
        result = {
            'test_folder': test_folder.name,
            'entry_point': None,
            'third_party_method': None,
            'execution_successful': False,
            'target_method_reached': False,
            'failure_type': None,
            'error': None
        }
        
        test_detail = {
            'test_file_path': None,
            'test_content': None,
            'maven_output': None
        }
        
        try:
            # Step 1: Load metadata
            metadata = self.load_metadata(test_folder)
            if not metadata:
                result['error'] = "Failed to load metadata"
                self.test_details.append(test_detail)
                return result
            
            entry_point = metadata.get('entry_point')
            third_party_method = metadata.get('third_party_method')
            
            if not entry_point or not third_party_method:
                result['error'] = "Missing entry_point or third_party_method in metadata"
                self.test_details.append(test_detail)
                return result
            
            result['entry_point'] = entry_point
            result['third_party_method'] = third_party_method
            
            # Step 2: Find test file
            test_file = self.find_test_file(test_folder)
            if not test_file:
                result['error'] = "No test file found"
                self.test_details.append(test_detail)
                return result
            
            # Record test file path relative to tests directory
            relative_path = test_file.relative_to(self.tests_dir)
            test_detail['test_file_path'] = str(relative_path)
            
            # Read test file content
            try:
                with open(test_file, 'r', encoding='utf-8') as f:
                    test_detail['test_content'] = f.read()
            except Exception as e:
                logger.warning(f"Failed to read test file content: {e}")
                test_detail['test_content'] = f"Error reading file: {e}"
            
            # Step 3: Parse entry point
            entry_info = self.parse_entry_point(entry_point)
            
            # Step 4: Get test class name from the actual Java file
            test_class = self.get_test_class_name(test_file)
            logger.info(f"Test class: {test_class}")
            
            # Step 5: Determine destination and copy test file
            destination_dir = self.get_test_destination_path(test_file, test_class)
            copied_file = self.copy_test_file(test_file, destination_dir)
            
            try:
                # Step 6: Run coverage check
                exit_code, stdout, stderr = self.run_coverage_check(
                    test_class=test_class,
                    method_class=entry_info['fully_qualified_class'],
                    target_method=third_party_method
                )
                
                # Extract Maven output
                maven_output = self.extract_maven_output(stdout, stderr)
                test_detail['maven_output'] = maven_output
                
                # Step 7: Log execution (stdout now includes Maven output)
                self.log_execution(test_folder.name, stdout, stderr, exit_code)
                
                # Step 8: Parse results
                coverage_result = self.parse_coverage_result(stdout, stderr, exit_code)
                result['execution_successful'] = coverage_result['execution_successful']
                result['target_method_reached'] = coverage_result['target_method_reached']
                result['failure_type'] = coverage_result['failure_type']
                
                if exit_code == 2 and not result['failure_type']:
                    result['error'] = "Script execution error"
                
                # Add test detail to list
                self.test_details.append(test_detail)
                
            finally:
                # Step 9: Clean up - remove copied test file
                if copied_file.exists():
                    copied_file.unlink()
                    logger.info(f"Removed copied test file: {copied_file}")
                
                # Remove empty directories
                try:
                    if destination_dir.exists() and not any(destination_dir.iterdir()):
                        destination_dir.rmdir()
                        logger.info(f"Removed empty directory: {destination_dir}")
                except:
                    pass
        
        except Exception as e:
            logger.error(f"Error processing {test_folder.name}: {e}", exc_info=True)
            result['error'] = str(e)
            # Add test detail even on exception
            self.test_details.append(test_detail)
        
        return result

    def run(self):
        """Main orchestration method"""
        logger.info("Starting test coverage orchestration")
        logger.info(f"Tests directory: {self.tests_dir}")
        logger.info(f"Project directory: {self.project_dir}")
        logger.info(f"Output JSON: {self.output_json}")
        logger.info(f"Log file: {self.log_file}")
        
        # Initialize log file
        with open(self.log_file, 'w', encoding='utf-8') as f:
            f.write(f"Test Execution Log\n")
            f.write(f"Started: {datetime.now().isoformat()}\n")
            f.write(f"{'='*80}\n\n")
        
        # Find all test folders
        test_folders = self.find_test_folders()
        
        # Process each test folder
        for i, test_folder in enumerate(test_folders, 1):
            logger.info(f"\n[{i}/{len(test_folders)}] Processing {test_folder.name}")
            result = self.process_test_folder(test_folder)
            self.results.append(result)
            
            # Save intermediate results for both files
            self.save_results()
            self.save_test_details()
        
        # Final summary
        logger.info(f"\n{'='*80}")
        logger.info("SUMMARY")
        logger.info(f"{'='*80}")
        logger.info(f"Total tests processed: {len(self.results)}")
        
        successful = sum(1 for r in self.results if r['execution_successful'])
        logger.info(f"Successful executions: {successful}")
        
        covered = sum(1 for r in self.results if r['target_method_reached'])
        logger.info(f"Target methods reached: {covered}")
        
        errors = sum(1 for r in self.results if r['error'])
        logger.info(f"Errors: {errors}")
        
        # Failure type breakdown
        compilation_failures = sum(1 for r in self.results if r['failure_type'] == 'compilation_failure')
        assertion_failures = sum(1 for r in self.results if r['failure_type'] == 'assertion_failure')
        runtime_errors = sum(1 for r in self.results if r['failure_type'] == 'test_runtime_error')
        
        if compilation_failures > 0:
            logger.info(f"Compilation failures: {compilation_failures}")
        if assertion_failures > 0:
            logger.info(f"Assertion failures: {assertion_failures}")
        if runtime_errors > 0:
            logger.info(f"Test runtime errors: {runtime_errors}")
        
        logger.info(f"\nResults saved to: {self.output_json}")
        details_json = self.output_json.parent / f"{self.output_json.stem}_details.json"
        logger.info(f"Test details saved to: {details_json}")
        logger.info(f"Logs saved to: {self.log_file}")


def main():
    parser = argparse.ArgumentParser(
        description='Orchestrate test execution and coverage checking for multiple test cases'
    )
    parser.add_argument(
        '--tests-dir',
        required=True,
        help='Path to directory containing test folders'
    )
    parser.add_argument(
        '--project-dir',
        required=True,
        help='Path to the Maven project root directory'
    )
    parser.add_argument(
        '--feedback-script',
        required=True,
        help='Path to feedback_score.py script'
    )
    parser.add_argument(
        '--output-json',
        default='results.json',
        help='Path to output JSON file (default: results.json)'
    )
    parser.add_argument(
        '--log-file',
        default='test_exec_log.txt',
        help='Path to execution log file (default: test_exec_log.txt)'
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
        orchestrator = TestOrchestrator(
            tests_dir=args.tests_dir,
            project_dir=args.project_dir,
            feedback_script=args.feedback_script,
            output_json=args.output_json,
            log_file=args.log_file
        )
        orchestrator.run()
        
        logger.info("\n✓ Orchestration completed successfully")
        sys.exit(0)
        
    except Exception as e:
        logger.error(f"Orchestration failed: {e}", exc_info=args.debug)
        sys.exit(1)


if __name__ == "__main__":
    main()