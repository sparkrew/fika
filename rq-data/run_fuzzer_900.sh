#!/usr/bin/env bash
set -euo pipefail


MODULE_PATH=""
CLASSPATH=""
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

JAZZER="$SCRIPT_DIR/jazzer"
JAZZER_AGENT="$SCRIPT_DIR/jazzer_standalone.jar"

COVERAGE_DIR="$MODULE_PATH/fuzz-coverage-reports"
REPORT_FILE="$MODULE_PATH/fuzzer_report.json"

FUZZER_JSON="$MODULE_PATH/fuzzer.json"

# Fixed fuzzing time: 15 minutes = 900 seconds
DEFAULT_MAX_TIME=900



# Validate files
if [ ! -f "$FUZZER_JSON" ]; then
    echo "ERROR: fuzzer.json not found at $FUZZER_JSON" >&2
    exit 1
fi

if [ ! -f "$JAZZER" ]; then
    echo "ERROR: Jazzer not found at $JAZZER" >&2
    exit 1
fi

if [ ! -f "$JAZZER_AGENT" ]; then
    echo "ERROR: Jazzer agent not found at $JAZZER_AGENT" >&2
    exit 1
fi

if [ ! -f "$CLASSPATH" ]; then
    echo "ERROR: Flink runtime JAR not found at $CLASSPATH" >&2
    exit 1
fi


echo "[*] Using fuzzer configuration: $FUZZER_JSON"
echo "[*] Fuzzing time per target: 15 minutes (900 seconds)"

mkdir -p "$COVERAGE_DIR"



# Step 1: Extract targets from fuzzer.json
TARGETS=$(python3 - "$FUZZER_JSON" "$DEFAULT_MAX_TIME" <<'PYEOF'
import json
import sys

fuzzer_path = sys.argv[1]
default_time = int(sys.argv[2])

with open(fuzzer_path) as f:
    fuzzer_entries = json.load(f)

results = []

for fe in fuzzer_entries:

    entry_point = fe["entryPoint"]
    direct_caller = fe["directCaller"]

    # Remove method parameters.
    #
    # Example:
    #   org.foo.Class.method(java.lang.String)
    #
    # becomes:
    #   org.foo.Class.method
    no_params = entry_point.split("(")[0]

    source_class, source_method = no_params.rsplit(".", 1)

    # Jazzer autofuzz format:
    #
    #   org.foo.Class::method
    #
    # Constructors:
    #
    #   org.foo.Class::<init>
    # becomes:
    #   org.foo.Class::new
    #
    autofuzz_ref = source_class + "::" + (
        "new" if source_method == "<init>" else source_method
    )

    # Determine sink class from directCaller.
    dc_no_params = direct_caller.split("(")[0]
    sink_class = dc_no_params.rsplit(".", 1)[0]

    results.append({
        "entryPoint": entry_point,
        "autofuzzRef": autofuzz_ref,
        "thirdPartyMethod": fe["thirdPartyMethod"],
        "directCaller": direct_caller,
        "lineNumber": fe["lineNumber"],
        "sourceClass": source_class,
        "sinkClass": sink_class,

        # ALWAYS 15 minutes.
        "maxTime": default_time
    })

print(json.dumps(results))
PYEOF
)


NUM_TARGETS=$(echo "$TARGETS" | python3 -c '
import json
import sys
print(len(json.load(sys.stdin)))
')


echo "[*] Found $NUM_TARGETS fuzzing targets"
echo ""


if [ "$NUM_TARGETS" -eq 0 ]; then
    echo "[!] No fuzzing targets found in $FUZZER_JSON"
    exit 0
fi



# Step 2: Run Jazzer for each target
RESULTS="[]"

for IDX in $(seq 0 $((NUM_TARGETS - 1))); do

    TARGET=$(echo "$TARGETS" | python3 -c "
import json
import sys

targets = json.load(sys.stdin)
print(json.dumps(targets[$IDX]))
")

    ENTRY_POINT=$(echo "$TARGET" | python3 -c '
import json
import sys
print(json.load(sys.stdin)["entryPoint"])
')

    AUTOFUZZ_REF=$(echo "$TARGET" | python3 -c '
import json
import sys
print(json.load(sys.stdin)["autofuzzRef"])
')

    THIRD_PARTY=$(echo "$TARGET" | python3 -c '
import json
import sys
print(json.load(sys.stdin)["thirdPartyMethod"])
')

    DIRECT_CALLER=$(echo "$TARGET" | python3 -c '
import json
import sys
print(json.load(sys.stdin)["directCaller"])
')

    LINE_NUMBER=$(echo "$TARGET" | python3 -c '
import json
import sys
print(json.load(sys.stdin)["lineNumber"])
')

    SOURCE_CLASS=$(echo "$TARGET" | python3 -c '
import json
import sys
print(json.load(sys.stdin)["sourceClass"])
')

    SINK_CLASS=$(echo "$TARGET" | python3 -c '
import json
import sys
print(json.load(sys.stdin)["sinkClass"])
')

    MAX_TIME=$(echo "$TARGET" | python3 -c '
import json
import sys
print(json.load(sys.stdin)["maxTime"])
')


    
    # Output paths
    REPORT_NAME="${SOURCE_CLASS##*.}_${LINE_NUMBER}"

    TEXT_REPORT="$COVERAGE_DIR/${REPORT_NAME}_report.txt"

    TARGET_FILENAME="${SINK_CLASS##*.}.java"

    CORPUS_DIR="$COVERAGE_DIR/${REPORT_NAME}_corpus"

    EXEC_FILE="$COVERAGE_DIR/${REPORT_NAME}.exec"

    mkdir -p "$CORPUS_DIR"


    
    # Target information
    echo "[*] Target $((IDX + 1))/$NUM_TARGETS"
    echo "[*] SOURCE ENTRYPOINT : $ENTRY_POINT"
    echo "[*] DIRECT CALLER     : $DIRECT_CALLER"
    echo "[*] THIRD PARTY METHOD: $THIRD_PARTY"
    echo "[*] TARGET LINE       : $LINE_NUMBER"
    echo "[*] SOURCE CLASS      : $SOURCE_CLASS"
    echo "[*] SINK CLASS        : $SINK_CLASS"
    echo "[*] AUTOFUZZ REF      : $AUTOFUZZ_REF"
    echo "[*] MAX TIME          : ${MAX_TIME}s (15 minutes)"
    echo ""


    
    # Run Jazzer
    FUZZ_EXIT=0
    CRASH_FOUND="false"

    "$JAZZER" \
        --cp="$CLASSPATH" \
        --autofuzz="$AUTOFUZZ_REF" \
        --agent_path="$JAZZER_AGENT" \
        --instrument="$SOURCE_CLASS:$SINK_CLASS" \
        --autofuzz_ignore="java.lang.NullPointerException,java.lang.IllegalArgumentException,java.lang.ClassNotFoundException" \
        --keep_going=999 \
        --coverage_report="$TEXT_REPORT" \
        --coverage_dump="$EXEC_FILE" \
        -max_total_time="$MAX_TIME" \
        "$CORPUS_DIR" \
        2>&1 || FUZZ_EXIT=$?


    if [ "$FUZZ_EXIT" -ne 0 ]; then
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

            
            # Check missed lines
            UNCOVERED_BLOCK=$(
                sed -n \
                    "/Missed lines:/,/^[A-Za-z]/ {
                        /^${TARGET_FILENAME}:/p
                    }" \
                    "$TEXT_REPORT" || true
            )

            UNCOVERED_NUMS=$(
                echo "$UNCOVERED_BLOCK" |
                sed -E 's/.*\[(.*)\].*/\1/' |
                sed 's/ //g'
            )


            if [ -n "$UNCOVERED_NUMS" ] && \
               echo "$UNCOVERED_NUMS" |
               grep -q -E "(^|,)${LINE_NUMBER}(,|$)"; then

                COVERAGE_STATUS="line_uncovered"
                COVERAGE_DETAIL="Line $LINE_NUMBER was never reached"


            else

                # Check incompletely covered lines
                INCOMPLETE_BLOCK=$(
                    sed -n \
                        "/Incompletely covered lines:/,/^[A-Za-z]/ {
                            /^${TARGET_FILENAME}:/p
                        }" \
                        "$TEXT_REPORT" || true
                )

                INCOMPLETE_NUMS=$(
                    echo "$INCOMPLETE_BLOCK" |
                    sed -E 's/.*\[(.*)\].*/\1/' |
                    sed 's/ //g'
                )


                if [ -n "$INCOMPLETE_NUMS" ] && \
                   echo "$INCOMPLETE_NUMS" |
                   grep -q -E "(^|,)${LINE_NUMBER}(,|$)"; then

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

    if [ "$COVERAGE_STATUS" = "covered" ] || \
       [ "$COVERAGE_STATUS" = "partial" ]; then

        FUZZER_SUCCESS="true"

    fi


    
    # Append result
    RESULTS=$(python3 -c '
import json
import sys

results = json.loads(sys.argv[1])

results.append({
    "entryPoint": sys.argv[2],
    "directCaller": sys.argv[3],
    "thirdPartyMethod": sys.argv[4],
    "lineNumber": int(sys.argv[5]),
    "maxTime": int(sys.argv[6]),
    "coverageStatus": sys.argv[7],
    "coverageDetail": sys.argv[8],
    "crashFound": sys.argv[9] == "true",
    "fuzzerSuccess": sys.argv[10] == "true",
    "coverageReportFile": sys.argv[11]
})

print(json.dumps(results))
' \
        "$RESULTS" \
        "$ENTRY_POINT" \
        "$DIRECT_CALLER" \
        "$THIRD_PARTY" \
        "$LINE_NUMBER" \
        "$MAX_TIME" \
        "$COVERAGE_STATUS" \
        "$COVERAGE_DETAIL" \
        "$CRASH_FOUND" \
        "$FUZZER_SUCCESS" \
        "$TEXT_REPORT"
    )

done



# Step 4: Write final report
echo "$RESULTS" | python3 -m json.tool > "$REPORT_FILE"



# Step 5: Summary
echo ""
echo "[*] SUMMARY"

python3 - "$REPORT_FILE" <<'PYEOF'
import json
import sys

report_file = sys.argv[1]

with open(report_file) as f:
    results = json.load(f)

total = len(results)

success = sum(
    1
    for r in results
    if r["fuzzerSuccess"]
)

crashes = sum(
    1
    for r in results
    if r["crashFound"]
)

print(f"  Total targets : {total}")
print(f"  Sink reached  : {success}/{total}")
print(f"  Crashes found : {crashes}")
print()

for r in results:

    status = "OK" if r["fuzzerSuccess"] else "FAIL"

    ep = (
        r["entryPoint"]
        .split("(")[0]
        .rsplit(".", 1)[-1]
    )

    dc = (
        r["directCaller"]
        .split("(")[0]
        .rsplit(".", 1)[-1]
    )

    print(
        f'  [{status:4s}] '
        f'{ep} -> {dc} '
        f'(line {r["lineNumber"]}, '
        f'{r["maxTime"]}s, '
        f'{r["coverageStatus"]})'
    )

PYEOF


echo ""
echo "[*] Full report: $REPORT_FILE"
