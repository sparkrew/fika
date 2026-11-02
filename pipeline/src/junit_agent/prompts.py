
SYSTEM_PROMPT = """You are an expert Java developer who writes good unit tests in order to maximize code coverage. You are specialized in Maven and JUnit 5 testing framework."""

USER_PROMPT_TEMPLATE = """Generate a JUnit 5 test that executes the full chain of method calls starting from the specified entry point method and reaching the specified third-party method. No assertions, inspections, or verifications are required. The test’s only goal is to try invoking the given third-party method during execution.

You are provided with:
entryPoint: Fully qualified public method where execution must begin.
thirdPartyMethod: Fully qualified third-party method that must be invoked.
path: Ordered list of method calls that must be traversed during execution.
methodSources: Complete and exact source code for all methods in the call chain.
constructors: All constructors of the class containing the entryPoint. Use one of these constructors to instantiate the class in the test. Then use that instance to call the entryPoint method.
fieldDeclarations: Instance variables and class variables of the class containing the entryPoint. These are the fields that can be accessed or set when creating the test.
setters: All setters of the entryPoint class that can modify the declared fields.
imports: All non-core-java imports that may be required by the test.

entryPoint: {entryPoint}
thirdPartyMethod: {thirdPartyMethod}
path: {path}
methodSources:
{methodSources}
constructors:
{constructors}
fieldDeclarations:
{fieldDeclarations}
setters:
{setters}
imports: {imports}

Hard constraints:
- The test class MUST NOT extend any other class (no ‘extends’ keyword).
- The test class MUST NOT override any methods (no @Override annotations).
- Do NOT create anonymous inner classes (e.g., new ClassName() {{ ... }}).
- Use real objects whenever possible.
- Use mocks only when necessary, and ONLY for objects required as constructor parameters when instantiating the class that contains the entry-point method, provided those objects are not directly related to the target method call.
- Do NOT mock or spy on the class under test.
- Do NOT alter, stub, or control the behavior of ANY method in the call path.
- Use only the following test-related libraries: junit-jupiter, mockito-core, and mockito-junit-jupiter. Do not use any other testing or mocking libraries.
- Do NOT add any assertions, verifications, or inspections of state/logs/output.
- package declaration MUST be: {test_package}
- class name MUST be: {test_class_name}
- exactly ONE @Test method
- Return ONLY the complete Java source code.
- Do NOT include explanations, markdown, or extra text.
"""


# ---------------------------------------------------------------------------
# Pure-LLM flow: the LLM receives ALL project source code and generates
# reachability tests for every third-party / dependency method it can find.
# ---------------------------------------------------------------------------

PURE_LLM_SYSTEM_PROMPT = """You are an expert Java developer who writes good unit tests in order to maximize code coverage. You are specialized in Maven and JUnit 5 testing framework. You are an expert at identifying third-party and dependency method calls in Java source code."""

PURE_LLM_USER_PROMPT_TEMPLATE = """You are given the complete source code of a Java project. Your task is to generate JUnit 5 reachability tests that exercise as many third-party / dependency method call sites as possible.

A "reachability test" is a test whose only goal is to invoke a specific third-party method during execution. No assertions, inspections, or verifications are required.

A "third-party / dependency method" is any method that belongs to an external library (not defined in this project’s own source code). Examples include calls to Apache Commons, Google Guava, Jackson, SLF4J, database drivers, HTTP clients, etc.

Instructions:
1. Analyze the provided source code.
2. Identify all call sites where a third-party / dependency method is invoked.
3. For each such call site, generate a separate JUnit 5 test class that triggers execution of that call site.
4. Each test class should have exactly ONE @Test method.
5. Name each test class descriptively: Reachability_<ClassName>_<MethodName>_<index>Test (e.g., Reachability_UserService_save_1Test).

You are provided with:
projectSourceCode: The complete source code of the Java project (all .java files).

projectSourceCode:
{projectSourceCode}

Hard constraints:
- Each test class MUST NOT extend any other class (no ‘extends’ keyword).
- Each test class MUST NOT override any methods (no @Override annotations).
- Do NOT create anonymous inner classes (e.g., new ClassName() {{{{ ... }}}}).
- Use real objects whenever possible.
- Use mocks only when necessary, and ONLY for objects required as constructor parameters, provided those objects are not directly related to the target method call.
- Do NOT mock or spy on the class under test.
- Do NOT alter, stub, or control the behavior of ANY method in the call path.
- Use only the following test-related libraries: junit-jupiter, mockito-core, and mockito-junit-jupiter. Do not use any other testing or mocking libraries.
- Do NOT add any assertions, verifications, or inspections of state/logs/output.
- package declaration MUST be: {test_package}
- exactly ONE @Test method per test class
- Return ONLY the complete Java source code for ALL test classes.
- Separate each test class with a line: // --- NEW TEST CLASS ---
- Do NOT include explanations, markdown, or extra text.
"""

PURE_LLM_RETRY_PROMPT_TEMPLATE = """The previously generated tests had compilation or runtime errors. Fix the issues below while keeping ALL hard constraints.

Compilation / runtime errors:
{errors}

Previously generated test code:
{previous_code}

The complete project source code (for reference):
{projectSourceCode}

Hard constraints (unchanged):
- Each test class MUST NOT extend any other class (no ‘extends’ keyword).
- Each test class MUST NOT override any methods (no @Override annotations).
- Do NOT create anonymous inner classes (e.g., new ClassName() {{{{ ... }}}}).
- Use real objects whenever possible.
- Use mocks only when necessary, and ONLY for objects required as constructor parameters, provided those objects are not directly related to the target method call.
- Do NOT mock or spy on the class under test.
- Do NOT alter, stub, or control the behavior of ANY method in the call path.
- Use only the following test-related libraries: junit-jupiter, mockito-core, and mockito-junit-jupiter. Do not use any other testing or mocking libraries.
- Do NOT add any assertions, verifications, or inspections of state/logs/output.
- package declaration MUST be: {test_package}
- exactly ONE @Test method per test class
- Return ONLY the complete Java source code for ALL test classes.
- Separate each test class with a line: // --- NEW TEST CLASS ---
- Do NOT include explanations, markdown, or extra text.
"""
