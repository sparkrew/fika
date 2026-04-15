#!/usr/bin/env python3
"""
Parse semgrep.json, fetch rule details from Semgrep SCA advisory API,
and save each rule as a JSON file organized by reachability status.

Usage:
    SEMGREP_TOKEN=your_token python3 parse_semgrep.py
    python3 parse_semgrep.py --input semgrep.json --token your_token
"""

import json
import os
import re
import argparse
import requests

#  CLI args
parser = argparse.ArgumentParser()
parser.add_argument("--input",  default="/semgrep.json", help="Path to semgrep.json")
parser.add_argument("--output", default="/rules",        help="Output base directory")
parser.add_argument("--token",  default="")
args = parser.parse_args()

HEADERS = {
    "Authorization": f"Bearer {args.token}",
    "Content-Type": "application/json",
}
API_URL = "https://semgrep.dev/api/sca/advisories/v2"

#  Create output folders 
for folder in ["reachable", "non-reachable", "unknown"]:
    os.makedirs(os.path.join(args.output, folder), exist_ok=True)

#  Load semgrep.json 
with open(args.input) as f:
    semgrep_data = json.load(f)

#  Extract unique rules with their reachability ─
# rule_id -> reachability value (may appear multiple times; keep first)
rules = {}
for result in semgrep_data.get("results", []):
    rule_id = result.get("check_id", "")
    if not re.match(r"ssc-[a-z0-9\-]+", rule_id):
        continue

    reachable = result.get("extra", {}) \
                      .get("sca_info", {}) \
                      .get("reachable", "unknown")

    if rule_id not in rules:
        rules[rule_id] = reachable  # first occurrence wins

print(f"Found {len(rules)} unique rules in {args.input}\n")

#  Fetch advisory details and save 
stats = {"reachable": 0, "non-reachable": 0, "unknown": 0, "error": 0}

for rule_id, reachable in rules.items():
    # Determine folder
    if reachable is True:
        folder = "reachable"
    elif reachable is False:
        folder = "non-reachable"
    else:
        folder = "unknown"

    print(f"Fetching {rule_id} → {folder}/")

    try:
        resp = requests.post(
            API_URL,
            headers=HEADERS,
            json={"cursor": 0, "limit": 25, "filter": {"query": rule_id}},
            timeout=60,
        )
        resp.raise_for_status()
        advisory_data = resp.json()
    except Exception as e:
        print(f"  ERROR fetching {rule_id}: {e}")
        stats["error"] += 1
        continue

    # Parse nested escaped JSON strings if present
    def unescape_nested(obj):
        """Recursively find string values that are JSON and parse them."""
        if isinstance(obj, dict):
            return {k: unescape_nested(v) for k, v in obj.items()}
        elif isinstance(obj, list):
            return [unescape_nested(i) for i in obj]
        elif isinstance(obj, str):
            stripped = obj.strip()
            if stripped.startswith("{") or stripped.startswith("["):
                try:
                    return unescape_nested(json.loads(stripped))
                except json.JSONDecodeError:
                    pass
        return obj

    advisory_data = unescape_nested(advisory_data)

    # Save to file
    out_path = os.path.join(args.output, folder, f"{rule_id}.json")
    with open(out_path, "w") as f:
        json.dump(advisory_data, f, indent=2)

    stats[folder] += 1
    print(f"  Saved → {out_path}")

print(f"""
Done!
  reachable:     {stats['reachable']}
  non-reachable: {stats['non-reachable']}
  unknown:       {stats['unknown']}
  errors:        {stats['error']}
""")