# Experiments

We have evaluated 08 projects with Fika.

[flink]()     
[pdfBox]()      
[graphhopper]()
[poi-tl]()        
[mybatis]()       
[tablesaw]()      
[tika]()
[checkstyle]()


## Steps for reproducing the results

Setup

- Java 17
- Maven 3.9.11

1. git clone https://github.com/sparkrew/fika
2. cd fika
3. mvn clean install

Then, for each project, run the following steps:

1. git clone the GitHub projects
2. run mvn clean install
3. check if it was successful
4. if unsuccessful, try git checkout to the latest tag (because tags tend to be more stable) and repeat step 2
5. if it is still unsuccessful, discard the project
6. if it is successful, continue to the next steps 
7. if it is a multi-module project, the module we consider is indicated after the hyphen in the link above 
8. cd to the module directory for those multi-module projects (for single module projects just stay under the root folder.)
9. check if the project has jacoco plugin configured in the pom.xml 
10. if not, add the jacoco plugin configuration to the pom.xml as given below. This should place under the <build><plugins> section of the pom.xml. If it is placed under any other tag (pluginManagement, profiles etc.), it will not work. So, please double check.
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
1.  Check if the surefire plugin is configured in the pom (<artifactId>maven-surefire-plugin</artifactId>). This can be also under the root pom.xml or the module pom.xml. This plugin should definitely be there. Then, check if it has a line similar to <configuration><argLine>something here</argLine>. IF it is there and it does not contain, @{argLine}, add that part. So for example, if the existing arline is, ` <argLine>@{surefireArgLine} -Xmx768m</argLine>` update it to ` <argLine>@{argLine} @{surefireArgLine} -Xmx768m</argLine>`. If the <argLine> tag is not there, no need to change anything. This change is required for jacoco to work properly with surefire.
2.  run mvn clean test. This should create jacoco reports under the folder target/site/jacoco/. IF not please troubleshoot. 
3.  Now we can finally start running the tool. 
4.  run the preprocessor with the following command. Make sure you run under the correct folder (if it is a multi-module project it should be under the module, otherwise it should be the project root folder). Adjust the paths as needed.
```bash
mvn io.github.sparkrew:preprocessor-maven-plugin:1.0-SNAPSHOT:preprocess -DoutputFile=/add-project-path-here/package-map.json
```
5.  run the api-finder by running the following command. Adjust the paths as needed.
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
1.  Once you run this, a successful attempt should create the following reports.
- coverage.json
- third_party_apis_full_methods.json
- 
These reports should be under the current folder where you ran the api-finder command.

6. run the test generation pipeline with the following commands.        
Note: The genrated tests may use the mockito library. Please make sure to add mockito-core to the project, if it is not already added.

```bash
python -m venv .venv
source .venv/bin/activate

python3 -m junit_agent.main /path/to/maven-project input.json --api deepseek --model deepseek-chat --log-file agent_graphhopper.log --api-key api-key --all
```

All succefully generated tests for each project can be found in the following folked repositories.
These repos also include all json reports created by the api-finder in the root folder of the corresponding module, and the output logs generated by the test generation pipeline inside the folder all_pipeline_logs.

[flink]()     
[pdfBox]()      
[graphhopper]()
[poi-tl]()        
[mybatis]()       
[tablesaw]()      
[tika]()
[checkstyle]()

