# Fika

Fika is a tool that performs static and dynamic reachability analysis of third-party methods within a project. It identifies uncovered third-party API calls in Java projects and generates the information required to create tests for them with the help of LLMs.

## How it works

**Components**: Fika consists of a preprocessor, an api-finder, and a test-generator.

- **Preprocessor** is a Maven plugin that creates a mapping of dependencies and package names at project build time. 
    This lets us identify all the package names of the dependencies used in the project. Currently, the format of the
    mapping is a JSON file with each package name mapped to its corresponding dependencies:
```json
{
  "org.bouncycastle.oer.its.etsi103097": [
    "org.bouncycastle:bcutil-jdk18on:jar:1.81"
  ],
  "org.mockito.internal.framework": [ 
    "org.mockito:mockito-core:jar:5.19.0" 
  ]
}
```
  However, sometimes multiple dependencies could have a package with the same name. In these cases, the most accurate 
  way to determine the correct dependency is through dynamic analysis. At this stage, we just record all the dependency 
  names without trying to figure out which classes are actually loaded by the classloader. For Fika, the actual dependency ID is not of importance.

- **API-finder** scans the source code of the project to identify all the third-party API calls. The output is a 
  JSON list of all the third-party API calls found in the source code in the following format. More details about the API-finder can be found [here](api-finder/README.md).

```json
{
    "entryPoint": "the fully qualified name of the public method that ultimately triggers the third-party library method.",
    "thirdPartyMethod": "the fully qualified name of the third-party method that must be invoked.",
    "path": [ "an ordered list of method calls from the entry point to the third-party method." ],
    "methodSources": [ "the complete source code of all relevant methods in the call chain." ],
    "constructors": [ "all constructors of the class that contains the entry-point method." ],
    "setters": ["all setters of the class that contains the entry-point method, if any."],
    "getters": [ "all getters of the class that contains the entry-point method, if any." ],
    "imports": [ "imports that might be relevant for implementing the test - this includes all non-java imports that are involved in any method along the path, if any." ],
    "testTemplate": "a basic template for a test class with the package name, test class name and test method name.",
    "conditionCount": "an integer that indicates the number of control flow conditions (if, for, while, switch, etc.) in all methods along the path.",
    "callCount": "the number of times the target third-party method appears within the immediate caller method."
}
```

 - **Test-generator** takes the list of uncovered third-party API calls and creates a prompt. When passing code blocks to the prompt, the test generator converts newline or tab characters (\n, \t) accordingly. It then passes this prompt to an LLM to generate tests that cover these API calls. The test generator also integrates and executes the generated tests against the project. If the tests cannot be executed successfully or fail to reach the target, it retries until either successful tests are generated or the maximum number of retries is reached. More details about the test generator can be found [here](pipeline/README.md).

## Usage

1. Clone and build the project (Java 17+ and Maven 3+ required)

```bash
git clone https://github.com/sparkrew/fika.git
mvn clean install
```

2. Run the preprocessor on your project

```bash
cd path/to/your/maven/project
mvn io.github.sparkrew:preprocessor-maven-plugin:1.0-SNAPSHOT:preprocess -DoutputFile=path/to/output/file.json
```

3. Run the api-finder on your project

```bash
java -jar path/to/api-finder/target/api-finder-1.0-SNAPSHOT-jar-with-dependencies.jar process -m path/to/preprocessor/output/file.json -p package.name -j path/to/project/jar/with/dependencies -c path/to/jacoco/report/directory -s path/to/project/src/main/java 
```
If JaCoCo reports do not exist, please run the tests with [JaCoCo enabled](https://www.eclemma.org/jacoco/trunk/doc/maven.html) first.

If any package name should be ignored (if there are submodules which should not be considered as third-party dependencies), add them to the file [api-finder/src/main/resources/ignored_packages.txt](api-finder/src/main/resources/ignored_packages.txt), one package name per line.

## Future work

- Evaluate the tool on a set of open source projects
    - Select a set of open source projects that use third party dependencies
    - Run the tool on these projects and measure the increase in coverage of third party API calls
    - Analyze the results
