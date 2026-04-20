# Experiments with Semgrep

## Case Studies

We analyze the same eight projects used in [Experiments.md](Experiments.md).

- Semgrep reported at least one reachable CVE in `poi-tl` at the selected commit.
- For the other projects, we moved to the oldest commits that still compile and run with Java 17+ and JUnit 5.
- These older commits give Semgrep a better chance of finding vulnerable dependencies, which lets us compare more reachability cases with Fika.

| Project | Commit Hash |
| ------- | ----------- |
| [flink](https://github.com/apache/flink) | 8af0259 |
| [graphhopper](https://github.com/graphhopper/graphhopper) | c721fa3 |
| [jooby](https://github.com/jooby-project/jooby) | 7dc5b6d |
| [poi-tl](https://github.com/Sayi/poi-tl) | 58fdb6c |
| [mybatis-3](https://github.com/mybatis/mybatis-3) | 57c7c41 |
| [pdfbox](https://github.com/apache/pdfbox) | f7a84ec |
| [tablesaw](https://github.com/jtablesaw/tablesaw) | faf0d54 |
| [tika](https://github.com/apache/tika) | bb785a2 |


The following modules are the ones for which Semgrep reported at least one reachable or undetermined CVE.

| Project         | Module(s)     | Commit Hash |
| --------------- | ---------- | ----------- |
| [flink](https://github.com/apache/flink)       | flink-parquet, flink-runtime, flink-protobuf, flink-orc, flink-metrics-datadog | 8af0259 |
| [graphhopper](https://github.com/graphhopper/graphhopper) | core, web-bundle | c721fa3 |
| [jooby](https://github.com/jooby-project/jooby)  | jooby-netty, jooby-jetty, jooby-http2-netty, jooby-http2-jetty, jooby-utow | 7dc5b6d |
| [poi-tl](https://github.com/Sayi/poi-tl)      | poi-tl | 58fdb6c |

- For each module, we extract the vulnerable method pattern from Semgrep's rule when available.
- If a rule has no explicit pattern, we check for any method coming from the vulnerable dependency instead.

## Methodology

### Setup Requirements
- Java 17 or higher
- Maven 3.9.11
- Semgrep CLI

### Steps for (re)producing the results

**1. Initial Setup**
```bash
git clone https://github.com/sparkrew/fika
cd fika
mvn clean install
```

**2. Clone and Prepare Target Project**
- Clone the project repository
- Check out the commit hash specified in the table above
- For multi-module projects, navigate to the module directory
- Run `mvn clean install`

**3. Run Semgrep Analysis**
Execute Semgrep to identify reachable vulnerabilities:
```bash
semgrep ci --allow-local-builds --json --output=semgrep.json
jq . semgrep.json > semgrep_formatted.json
```

**4. Download and Extract Vulnerability Patterns**
- Run the rule extraction script to identify vulnerable methods:
```bash
python rq-data/semgrep.py
```
- This identifies third-party methods referenced in each Semgrep rule

**5. Generate Reachability Scenarios with Fika**
- Follow the standard Fika pipeline from [Experiments.md](Experiments.md) to generate:
  - `package-map.json` using the preprocessor
  - API analysis using api-finder
  - Coverage data and third-party method calls
- Run the reachability scenario generation pipeline

**6. Verify Executability**
- Cross-check identified vulnerable methods against Fika's executability analysis
- Validate that vulnerable methods reported by Semgrep are:
  - present in the codebase
    - executable (have at least one call path from reachable entry points)
    - unreachable (cannot be executed from any entry point)
  - not present in the codebase

## Results

The forked repositories with all analysis outputs are available at:
- [flink](https://github.com/yogyagamage/flink/tree/semgrep-uc)
- [graphhopper](https://github.com/yogyagamage/graphhopper/tree/semgrep-uc)
- [jooby](https://github.com/yogyagamage/jooby/tree/semgrep-uc)
- [poi-tl](https://github.com/yogyagamage/poi-tl/tree/semgrep-uc)

### Fika Outputs
Each fork includes:
- **Reachability scenario logs**: `semgrep-uc/module/test_generation_data/`
- **Generated reachability scenarios**: inside the module's test folder
- **Third-party call-site paths**: `semgrep-uc/module/third_party_apis_full_methods.json`

### Semgrep Outputs

This repo (Fika) includes:
- **CLI outputs**: [rq-data/semgrep-data.pdf](rq-data/semgrep-data.pdf) (The logs produced by Semgrep CLI for all 13 modules)
- **Reachable or undetermined CVEs**: [rq-data/detailed-cve.pdf](rq-data/detailed-cve.pdf) (A detailed list of CVEs reported by Semgrep for each module which are either reachable or undetermined.)
  
Each fork includes:
- **Semgrep rules**: `semgrep-uc/module/rules/`
  - `reachable/` - rules that Semgrep marks as reachable or undetermined
  - `non-reachable/` - rules that Semgrep marks as unreachable

