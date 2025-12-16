import json
import re
import os
from collections import Counter
from pathlib import Path

def remove_comments(code):
    """Remove single-line and multi-line comments from Java code"""
    # Remove multi-line comments /* ... */
    code = re.sub(r'/\*.*?\*/', '', code, flags=re.DOTALL)
    # Remove single-line comments //
    code = re.sub(r'//.*?$', '', code, flags=re.MULTILINE)
    return code

def remove_string_literals(code):
    """Remove string literals to avoid false positives from keywords in strings"""
    # Remove string literals (both single and double quotes)
    code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', code)
    code = re.sub(r"'(?:[^'\\]|\\.)*'", "''", code)
    return code

def count_control_statements(method_code):
    """Count control/conditional statements in Java code"""
    if not method_code or method_code.strip() == "":
        return 0
    
    # Remove comments and string literals
    cleaned_code = remove_comments(method_code)
    cleaned_code = remove_string_literals(cleaned_code)
    
    # Count control statements using word boundaries to avoid partial matches
    # \b ensures we match whole words only
    patterns = [
        r'\bif\s*\(',      # if statements
        r'\bfor\s*\(',     # for loops
        r'\bwhile\s*\(',   # while loops
        r'\bswitch\s*\(',  # switch statements
        r'\bdo\s+\{',      # do-while loops (match the 'do' part)
    ]
    
    total_count = 0
    for pattern in patterns:
        matches = re.findall(pattern, cleaned_code)
        total_count += len(matches)
    
    return total_count

def analyze_single_file(json_file_path):
    """Analyze a single JSON file and return control statement counts"""
    
    try:
        with open(json_file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except Exception as e:
        print(f"Error reading {json_file_path}: {e}")
        return None
    
    # Counter to track: control_count -> number of records
    control_counts = Counter()
    
    # Process each record in fullMethodsPaths
    for record in data.get('fullMethodsPaths', []):
        full_methods = record.get('fullMethods', [])
        
        # Sum total control statements for all methods in this record
        total_control_count = 0
        for method in full_methods:
            total_control_count += count_control_statements(method)
        
        # Increment the counter for this record based on its total control count
        control_counts[total_control_count] += 1
    
    # Convert Counter to sorted dict
    return dict(sorted(control_counts.items()))

def analyze_all_subfolders(parent_folder, output_file):
    """Analyze all subfolders in the parent folder for JSON files"""
    
    parent_path = Path(parent_folder)
    
    if not parent_path.exists():
        print(f"Error: Folder '{parent_folder}' does not exist!")
        return
    
    # Dictionary to store results for all subfolders
    all_results = {}
    
    # Iterate through all items in the parent folder
    for item in parent_path.iterdir():
        # Only process directories, skip files
        if item.is_dir():
            subfolder_name = item.name
            json_file_path = item / "third_party_apis_full_methods.json"
            
            # Check if the JSON file exists in this subfolder
            if json_file_path.exists():
                print(f"Processing: {subfolder_name}/third_party_apis_full_methods.json")
                
                result = analyze_single_file(json_file_path)
                
                if result:
                    # Remove entries with 0 or 1 control statements if you only want 2+
                    # Uncomment the next line if you want to filter out 0 and 1
                    # result = {k: v for k, v in result.items() if k >= 2}
                    
                    # Convert keys to strings for consistent JSON output
                    all_results[subfolder_name] = {str(k): v for k, v in result.items()}
                    print(f"  Total records in this subfolder: {sum(result.values())}")
            else:
                print(f"Skipping: {subfolder_name} (no third_party_apis_full_methods.json found)")
    
    # Write consolidated results to output file
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(all_results, f, indent=2)
    
    # Print summary
    print(f"\n{'='*60}")
    print(f"Analysis complete!")
    print(f"Total subfolders processed: {len(all_results)}")
    print(f"\nResults written to: {output_file}")
    print(f"{'='*60}")

if __name__ == "__main__":
    parent_folder = "examples"
    output_file = "control_record.json"
    
    analyze_all_subfolders(parent_folder, output_file)