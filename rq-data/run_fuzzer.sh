#!/usr/bin/env bash
set -euo pipefail


MODULE_PATH=""
CLASSPATH=""
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAZZER="$SCRIPT_DIR/jazzer"
JAZZER_AGENT="$SCRIPT_DIR/jazzer_standalone.jar"
COVERAGE_DIR="$MODULE_PATH/fuzz-coverage-reports"
REPORT_FILE="$MODULE_PATH/fuzzer_report.json"
DEFAULT_MAX_TIME=60  # fallback if no matching JSONL record found


# Derived paths

FUZZER_JSON="$MODULE_PATH/fuzzer.json"
JSONL_DIR="$MODULE_PATH/test_generation_data"

if [ ! -f "$FUZZER_JSON" ]; then
    echo "ERROR: fuzzer.json not found at $FUZZER_JSON" >&2
    exit 1
fi

# Find the agent JSONL file (there should be one .jsonl in test_generation_data)
JSONL_FILE=$(find "$JSONL_DIR" -maxdepth 1 -name '*.jsonl' | head -n 1)
if [ -z "$JSONL_FILE" ]; then
    echo "ERROR: No .jsonl file found in $JSONL_DIR" >&2
    exit 1
fi
echo "[*] Using JSONL: $JSONL_FILE"

mkdir -p "$COVERAGE_DIR"


# Step 1: Extract targets and compute max_total_time from JSONL
# Outputs one JSON line per fuzzer entry with all needed fields

TARGETS=$(python3 - "$FUZZER_JSON" "$JSONL_FILE" "$DEFAULT_MAX_TIME" <<'PYEOF'
import json, sys
from datetime import datetime

fuzzer_path, jsonl_path, default_time = sys.argv[1], sys.argv[2], int(sys.argv[3])

with open(fuzzer_path) as f:
    fuzzer_entries = json.load(f)

with open(jsonl_path) as f:
    records = [json.loads(line) for line in f if line.strip()]

results = []
for fe in fuzzer_entries:
    matched_time = None
    for i, r in enumerate(records):
        fs = r.get("final_state", {})
        inp = r.get("input", {})
        if (fs.get("entryPoint") == fe["entryPoint"]
                and fs.get("directCaller") == fe["directCaller"]
                and fs.get("path") == fe["path"]
                and inp.get("thirdPartyMethod") == fe["thirdPartyMethod"]
                and fs.get("approved") is True):
            t1 = datetime.fromisoformat(r["timestamp"].rstrip("Z"))
            if i + 1 < len(records):
                t2 = datetime.fromisoformat(records[i + 1]["timestamp"].rstrip("Z"))
                matched_time = int((t2 - t1).total_seconds()) + 1
            break

    # "pkg.Class.method(params)" -> class="pkg.Class", method="method"
    no_params = fe["entryPoint"].split("(")[0]
    source_class = no_params.rsplit(".", 1)[0]
    source_method = no_params.rsplit(".", 1)[1]
    # Jazzer autofuzz format: "pkg.Class::method" or "pkg.Class::new" for constructors
    autofuzz_ref = source_class + "::" + ("new" if source_method == "<init>" else source_method)

    dc_no_params = fe["directCaller"].split("(")[0]
    sink_class = dc_no_params.rsplit(".", 1)[0]

    results.append({
        "entryPoint": fe["entryPoint"],
        "autofuzzRef": autofuzz_ref,
        "thirdPartyMethod": fe["thirdPartyMethod"],
        "directCaller": fe["directCaller"],
        "lineNumber": fe["lineNumber"],
        "sourceClass": source_class,
        "sinkClass": sink_class,
        "maxTime": matched_time if matched_time else default_time,
        "timeSource": "jsonl" if matched_time else "default",
    })

print(json.dumps(results))
PYEOF
)

NUM_TARGETS=$(echo "$TARGETS" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))")
echo "[*] Found $NUM_TARGETS fuzzing targets"
echo ""


# Step 2: Run jazzer for each target and collect results

RESULTS="[]"

for IDX in $(seq 0 $((NUM_TARGETS - 1))); do
    TARGET=$(echo "$TARGETS" | python3 -c "import json,sys; t=json.load(sys.stdin)[$IDX]; print(json.dumps(t))")

    ENTRY_POINT=$(echo "$TARGET" | python3 -c "import json,sys; print(json.load(sys.stdin)['entryPoint'])")
    AUTOFUZZ_REF=$(echo "$TARGET" | python3 -c "import json,sys; print(json.load(sys.stdin)['autofuzzRef'])")
    THIRD_PARTY=$(echo "$TARGET" | python3 -c "import json,sys; print(json.load(sys.stdin)['thirdPartyMethod'])")
    DIRECT_CALLER=$(echo "$TARGET" | python3 -c "import json,sys; print(json.load(sys.stdin)['directCaller'])")
    LINE_NUMBER=$(echo "$TARGET" | python3 -c "import json,sys; print(json.load(sys.stdin)['lineNumber'])")
    SOURCE_CLASS=$(echo "$TARGET" | python3 -c "import json,sys; print(json.load(sys.stdin)['sourceClass'])")
    SINK_CLASS=$(echo "$TARGET" | python3 -c "import json,sys; print(json.load(sys.stdin)['sinkClass'])")
    MAX_TIME=$(echo "$TARGET" | python3 -c "import json,sys; print(json.load(sys.stdin)['maxTime'])")
    TIME_SOURCE=$(echo "$TARGET" | python3 -c "import json,sys; print(json.load(sys.stdin)['timeSource'])")

    REPORT_NAME="${SOURCE_CLASS##*.}_${LINE_NUMBER}"
    TEXT_REPORT="$COVERAGE_DIR/${REPORT_NAME}_report.txt"
    TARGET_FILENAME="${SINK_CLASS##*.}.java"
    CORPUS_DIR="$COVERAGE_DIR/${REPORT_NAME}_corpus"

    mkdir -p "$CORPUS_DIR"

    echo "[*] Target $((IDX + 1))/$NUM_TARGETS"
    echo "[*] SOURCE ENTRYPOINT : $ENTRY_POINT"
    echo "[*] DIRECT CALLER     : $DIRECT_CALLER"
    echo "[*] THIRD PARTY METHOD: $THIRD_PARTY"
    echo "[*] TARGET LINE       : $LINE_NUMBER"
    echo "[*] MAX TIME          : ${MAX_TIME}s (source: $TIME_SOURCE)"

    FUZZ_EXIT=0
    CRASH_FOUND="false"

    "$JAZZER" \
      --cp="$CLASSPATH" \
      --autofuzz="$AUTOFUZZ_REF" \
      --agent_path="$JAZZER_AGENT" \
      --instrument="$SOURCE_CLASS:$SINK_CLASS" \
      --autofuzz_ignore="java.lang.NullPointerException,java.lang.IllegalArgumentException" \
      --keep_going=999 \
      --coverage_report="$TEXT_REPORT" \
      --coverage_dump="$COVERAGE_DIR/${REPORT_NAME}.exec" \
      -max_total_time="$MAX_TIME" \
      "$CORPUS_DIR" 2>&1 || FUZZ_EXIT=$?

    if [ $FUZZ_EXIT -ne 0 ]; then
        echo "[!] Jazzer exited with code $FUZZ_EXIT"
        CRASH_FOUND="true"
    fi

    
    # Step 3: Evaluate coverage
    
    COVERAGE_STATUS="unknown"
    COVERAGE_DETAIL=""

    if [ ! -f "$TEXT_REPORT" ]; then
        COVERAGE_STATUS="no_report"
        COVERAGE_DETAIL="Coverage report was not generated"
    else
        LINE_COV_INFO=$(grep "^${TARGET_FILENAME}:" "$TEXT_REPORT" | head -n 1 || true)

        if [ -z "$LINE_COV_INFO" ]; then
            COVERAGE_STATUS="class_not_tracked"
            COVERAGE_DETAIL="$TARGET_FILENAME not found in coverage report"
        elif [[ "$LINE_COV_INFO" == *"(0.00%)"* ]]; then
            COVERAGE_STATUS="uncovered_class"
            COVERAGE_DETAIL="$TARGET_FILENAME line coverage is 0.00%"
        else
            # Check missed lines section
            UNCOVERED_BLOCK=$(sed -n "/Missed lines:/,/^[A-Za-z]/ { /^${TARGET_FILENAME}:/p }" "$TEXT_REPORT" || true)
            UNCOVERED_NUMS=$(echo "$UNCOVERED_BLOCK" | sed -E 's/.*\[(.*)\].*/\1/' | sed 's/ //g')

            if [ -n "$UNCOVERED_NUMS" ] && echo "$UNCOVERED_NUMS" | grep -q -E "(^|,)${LINE_NUMBER}(,|$)"; then
                COVERAGE_STATUS="line_uncovered"
                COVERAGE_DETAIL="Line $LINE_NUMBER was never reached"
            else
                # Check incompletely covered lines section
                INCOMPLETE_BLOCK=$(sed -n "/Incompletely covered lines:/,/^[A-Za-z]/ { /^${TARGET_FILENAME}:/p }" "$TEXT_REPORT" || true)
                INCOMPLETE_NUMS=$(echo "$INCOMPLETE_BLOCK" | sed -E 's/.*\[(.*)\].*/\1/' | sed 's/ //g')

                if [ -n "$INCOMPLETE_NUMS" ] && echo "$INCOMPLETE_NUMS" | grep -q -E "(^|,)${LINE_NUMBER}(,|$)"; then
                    COVERAGE_STATUS="partial"
                    COVERAGE_DETAIL="Line $LINE_NUMBER reached but branches not fully exhausted"
                else
                    COVERAGE_STATUS="covered"
                    COVERAGE_DETAIL="Line $LINE_NUMBER fully covered"
                fi
            fi
        fi
    fi

    echo "[*] Coverage: $COVERAGE_STATUS — $COVERAGE_DETAIL"
    echo ""

    # Determine overall success
    FUZZER_SUCCESS="false"
    if [ "$COVERAGE_STATUS" = "covered" ] || [ "$COVERAGE_STATUS" = "partial" ]; then
        FUZZER_SUCCESS="true"
    fi

    # Append to results
    RESULTS=$(python3 -c "
import json, sys
results = json.loads(sys.argv[1])
results.append({
    'entryPoint': sys.argv[2],
    'directCaller': sys.argv[3],
    'thirdPartyMethod': sys.argv[4],
    'lineNumber': int(sys.argv[5]),
    'maxTime': int(sys.argv[6]),
    'timeSource': sys.argv[7],
    'coverageStatus': sys.argv[8],
    'coverageDetail': sys.argv[9],
    'crashFound': sys.argv[10] == 'true',
    'fuzzerSuccess': sys.argv[11] == 'true',
    'coverageReportFile': sys.argv[12],
})
print(json.dumps(results))
" "$RESULTS" "$ENTRY_POINT" "$DIRECT_CALLER" "$THIRD_PARTY" "$LINE_NUMBER" \
  "$MAX_TIME" "$TIME_SOURCE" "$COVERAGE_STATUS" "$COVERAGE_DETAIL" \
  "$CRASH_FOUND" "$FUZZER_SUCCESS" "$TEXT_REPORT")
done


# Step 4: Write final report

echo "$RESULTS" | python3 -m json.tool > "$REPORT_FILE"

echo "[*] SUMMARY"

python3 -c "
import json, sys
with open(sys.argv[1]) as f:
    results = json.load(f)
total = len(results)
success = sum(1 for r in results if r['fuzzerSuccess'])
crashes = sum(1 for r in results if r['crashFound'])
print(f'  Total targets : {total}')
print(f'  Sink reached  : {success}/{total}')
print(f'  Crashes found : {crashes}')
print()
for r in results:
    status = 'OK' if r['fuzzerSuccess'] else 'FAIL'
    ep = r['entryPoint'].split('(')[0].rsplit('.', 1)[-1]
    dc = r['directCaller'].split('(')[0].rsplit('.', 1)[-1]
    print(f'  [{status:4s}] {ep} -> {dc} (line {r[\"lineNumber\"]}, {r[\"maxTime\"]}s, {r[\"coverageStatus\"]})')
" "$REPORT_FILE"

echo ""
echo "[*] Full report: $REPORT_FILE"
