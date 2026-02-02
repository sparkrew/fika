# Experiments

We have analyzed 08 modules in 08 projects with Fika.

| Project         | Module     | Commit Hash |
| --------------- | ---------- | ----------- |
| [flink](https://github.com/apache/flink)       | flink-core |  61f9ffe    |
| [pdfBox](https://github.com/apache/pdfbox)      | pdfbox     |  3260022    |
| [graphhopper](https://github.com/graphhopper/graphhopper ) | core       |  1c811e5    |
| [poi-tl](https://github.com/Sayi/poi-tl)      | poi-tl     |  58fdb6c    |
| [mybatis](https://github.com/mybatis/mybatis-3)     | —          |  58fdb6c    |
| [tablesaw](https://github.com/jtablesaw/tablesaw)    | json       |  faf0d54    |
| [tika](https://github.com/apache/tika)        | tika-core  |  bb785a2    |
| [jooby](https://github.com/jooby-project/jooby)  | jooby          |     d2272e7    |


## Steps for (re)producing the results

Setup

- Java 21 for Jooby, and Java 17 for all other projects
- Maven 3.9.11

1. git clone https://github.com/sparkrew/fika
2. cd fika
3. mvn clean install

Then, for each project, run the following steps:

1. git clone the GitHub projects
2. checkout to the given commit hash
3. run mvn clean install
4. if it is a multi-module project, the module we consider is indicated after the hyphen in the link above 
5. cd to the module directory for those multi-module projects (for single module projects just stay under the root folder.)
6. check if the project has jacoco plugin configured in the pom.xml 
7. if not, add the jacoco plugin configuration to the pom.xml as given below. This should be placed under the <build><plugins> section of the pom.xml. If it is placed under any other tag (pluginManagement, profiles etc.), it will not work.
```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.14</version>
    <executions>
        <execution>
            <id>prepare-agent</id>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```
8.  Check if the surefire plugin is configured in the pom (<artifactId>maven-surefire-plugin</artifactId>). This can be also under the root pom.xml or the module pom.xml. Then, check if it has a line similar to <configuration><argLine>something here</argLine>. If it is there and it does not contain, @{argLine}, add that part. So for example, if the existing arline is, ` <argLine>@{surefireArgLine} -Xmx768m</argLine>` update it to ` <argLine>@{argLine} @{surefireArgLine} -Xmx768m</argLine>`. If the <argLine> tag is not there, no need to change anything. This change is required for jacoco to work properly with surefire.
9.  run mvn clean test. This should create jacoco reports under the folder target/site/jacoco/. IF not please troubleshoot. 
10.  run the preprocessor with the following command. Make sure you run under the correct folder (if it is a multi-module project it should be under the module, otherwise it should be the project root folder). Adjust the paths as needed.
```bash
mvn io.github.sparkrew:preprocessor-maven-plugin:1.0-SNAPSHOT:preprocess -DoutputFile=/add-project-path-here/package-map.json
```
11.  run the api-finder by running the following command. Adjust the paths as needed.
```bash
 java -jar /path-to-project/fika/api-finder/target/api-finder-1.0-SNAPSHOT-jar-with-dependencies.jar process -m /path-to-previously-created/package-map.json -p package-name -j /path-to-project-jar -c /project-path/target/site/jacoco -s /path-to-project-source-code
```
Here,
<br>
-m is for the package map file created in the previous step
<br>
-p is for the package name to be analyzed. For example, for pdfbox it can be "org.apache.pdfbox". You can find this by going to a class in the project and checking its package declaration. Package declaration is the line on top of a class such as "package org.apache.pdfbox"
<br>
-j is for the project jar file. You can find this under the target/ folder of the project after a successful build. It should be something like projectname-version.jar. Sometimes, some projects have a jar that contains all the dependencies with the name -projectname-version-jar-with-dependencies.jar. You MUST always use the jar with dependencies. If a separate jar does not exist we can assume the existing jar is the one with dependencies. You can clarify this by checking the pom file as well. (a quick google search or chatgpt will let you know.)
<br>
-c is for the jacoco report folder. This should be target/site/jacoco/ under the project folder.
<br>
-s is for the source code folder. This should be the main source code folder of the project. For example  /Users/username/Documents/pdfbox/pdfbox. Note that, we don't need to go to src/main/java. just passing the project root folder is enough. For a multi-module project such as the pdfbox in this example, the path should be the module folder (pdfbox/pdfbox in this case).

Once you run this, a successful attempt should create the following reports.
- coverage.json
- third_party_apis_full_methods.json
- all_third_party_call_pairs.json
- package-map.json      
These reports should be under the current folder where you ran the api-finder command.  

Upto this step, the results should be reproducible.

12. run the test generation pipeline with the following commands.        
Note: The genrated tests may use the mockito library. Please make sure to add mockito-core to the project, if it is not already added. Also, the generated tests may not follow the specific code formatting rules steup by the project. Therefore, you may need to disable them before running the pipeline.

```bash
python -m venv .venv
source .venv/bin/activate

python3 -m junit_agent.main /path/to/maven-project input.json --api deepseek --model deepseek-chat --log-file agent_graphhopper.log --api-key api-key --all
```

## Results
All successfully generated tests for each project are available in the following forked repositories.
(The generated tests may not be reproducible as LLM outputs are inherently non-deterministic.)

These forks include additional commits that introduce the required test plugins and dependencies when they were missing from the original repositories. Certain plugins enforcing custom rules such as checkstyle and spotless, were also disabled to allow the pipeline to run continuously. To enforce these cutom rules, some generated tests may require manual review and styling fixes.

Each fork also contains all JSON reports produced by the api-finder, located in the root directory of the corresponding module, as well as the output logs generated by the test generation pipeline under the ```all_pipeline_logs``` directory.       
Each ```all_pipeline_logs``` directory has 4 files:
- `agent_projectName.jsonl`: 
  - Includes
    - timestamp: when that record was written
    - test_case_index: which test case (the loop index)
    - input: a small subset of the input fields (currently only entryPoint and thirdPartyMethod)
    - final_state: the full final_state dict returned from the LangGraph stream (this is the agent’s final state for that test case, including keys like approved, iteration, java_source, last_run_output, trace, coverage flags, etc.)
    - iteration_log: the final_state["iteration_log"] list (a history across iterations) 
- `agent_projectName.log`: Similar to the above file but in textual, human readable format. Also contains maven output logs.
- `projectName.log`: Execution logs without internal details; mainly includes the final success/failure status, and the number of matching records.
- `prompts_projectName.log`: Contains all prompts.

The links to the forks:

[flink](https://github.com/yogyagamage/flink/tree/fika)            
[pdfBox](https://github.com/yogyagamage/pdfbox/tree/fika)              
[graphhopper](https://github.com/yogyagamage/graphhopper/tree/fika)            
[poi-tl](https://github.com/yogyagamage/poi-tl/tree/fika)                
[mybatis](https://github.com/yogyagamage/mybatis-3/tree/fika)               
[tablesaw](https://github.com/yogyagamage/tablesaw/tree/fika)              
[tika](https://github.com/yogyagamage/tika/tree/fika)        
[jooby](https://github.com/yogyagamage/jooby/tree/fika)        

