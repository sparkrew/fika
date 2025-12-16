## All GitHub project links

1. https://github.com/apache/pdfbox - pdfbox
2. https://github.com/Sayi/poi-tl - poi-tl
3. https://github.com/immutables/immutables - gson
4. https://github.com/google/jimfs - jmifs
5. https://github.com/jooby-project/jooby/tree/3.x - jooby
6. https://github.com/modelmapper/modelmapper - core
7. https://github.com/apache/flink - flink-core
8. https://github.com/graphhopper/graphhopper - core
9. https://github.com/google/guice - core
10. https://github.com/helidon-io/helidon - openapi
11. https://github.com/apache/httpcomponents-client - httpclient5
12. https://github.com/LibrePDF/OpenPDF - openpdf-core
13. https://github.com/pf4j/pf4j - pf4j
14. https://github.com/scribejava/scribejava - scribejava-core
15. https://github.com/jtablesaw/tablesaw - json
16. https://github.com/apache/tika - tika-core
17. https://github.com/undertow-io/undertow - core
    <br>
    <br>
18. https://github.com/radsz/jacop
19. https://github.com/DiUS/java-faker
20. https://github.com/jcabi/jcabi-github
21. https://github.com/checkstyle/checkstyle
22. https://github.com/OpenHFT/Chronicle-Map
23. https://github.com/classgraph/classgraph
    <br>
    <br>
24. https://github.com/apache/commons-validator
25. https://github.com/stanfordnlp/CoreNLP
26. https://github.com/redis/lettuce
27. https://github.com/mybatis/mybatis-3
28. https://github.com/FasterXML/woodstox

## Steps
Run once for all projects 
1. git clone https://github.com/sparkrew/fika
2. cd fika
3. mvn clean install

Then, for each project above, run the following steps:

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
11. Check if the surefire plugin is configured in the pom (<artifactId>maven-surefire-plugin</artifactId>). This can be also under the root pom.xml or the module pom.xml. This plugin should definitely be there. Then, check if it has a line similar to <configuration><argLine>something here</argLine>. IF it is there and it does not contain, @{argLine}, add that part. So for example, if the existing arline is, ` <argLine>@{surefireArgLine} -Xmx768m</argLine>` update it to ` <argLine>@{argLine} @{surefireArgLine} -Xmx768m</argLine>`. If the <argLine> tag is not there, no need to change anything. This change is required for jacoco to work properly with surefire.
12. run mvn clean test. This should create jacoco reports under the folder target/site/jacoco/. IF not please troubleshoot. 
13. Now we can finally start running the tool. 
14. run the preprocessor with the following command. Make sure you run under the correct folder (if it is a multi-module project it should be under the module, otherwise it should be the project root folder). Adjust the paths as needed.
```bash
mvn io.github.sparkrew:preprocessor-maven-plugin:1.0-SNAPSHOT:preprocess -DoutputFile=/add-project-path-here/package-map.json
```
15. run the api-finder by running the following command. Adjust the paths as needed.
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
16. Once you run this, a successful attempt should create the following reports.
- coverage.json
- path-stats.json
- third_party_apis_entry_point.json
- third_party_apis_full_methods.json
- third_party_apis_instrumentation_data.json
- third_party_apis_sliced.json
These reports should be under the current folder where you ran the api-finder command.
17. Repeat steps 1-16 for all the projects listed above.

## Draft Script for Ranking

For generating tests and checking the coverage improvement, you can use the feedback_score.py.
What it does is, it runs a given test class, generates jacoco reports for that class, then checks if a given third party method is covered by that test case or not. 
This is how you run it. 
```bash
python3 feedback_score.py --project-dir /path/to/project \
                                       --test-class com.example.MyTest \
                                       --method-class com.example.MyClass \
                                       --target-method org.thirdparty.Library.someMethod
```

You should pass 4 inputs.
<br>
--project-dir : path to the root folder of the project
<br>
--test-class : the fully qualified name of the test class to run
<br>
--method-class : the fully qualified name of the class that contains the target method (here the target method is the method that calls the third party method. In other words, this is the last method in the "path")
<br>
--target-method : the fully qualified name of the third party method to check coverage for.

For example,
```bash
python3 /Users/yogyagamage/Documents/UdeM/Courses/Testing/project/fika/api-finder/feedback_score.py --project-dir /Users/yogyagamage/Documents/UdeM/Courses/Testing/project/eval/pdfbox/pdfbox  \
--test-class org.apache.pdfbox.encryption.TestPublicKeyEncryption \
--method-class org.apache.pdfbox.pdmodel.PDDocument \
--target-method org.apache.commons.logging.LogFactory.getLog
```

Feel free to change this python script as needed.


