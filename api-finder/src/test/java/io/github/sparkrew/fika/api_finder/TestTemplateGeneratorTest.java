package io.github.sparkrew.fika.api_finder;

import io.github.sparkrew.fika.api_finder.model.ThirdPartyPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for TestTemplateGenerator class.
 */
@ExtendWith(MockitoExtension.class)
class TestTemplateGeneratorTest {

    @Test
    void testGenerateTestTemplate_BasicPath() {
        MethodSignature entryPoint = mock(MethodSignature.class);
        ClassType entryClassType = mock(ClassType.class);
        when(entryPoint.getDeclClassType()).thenReturn(entryClassType);
        when(entryClassType.getFullyQualifiedName()).thenReturn("com.example.service.UserService");
        when(entryPoint.getName()).thenReturn("createUser");

        MethodSignature thirdPartyMethod = mock(MethodSignature.class);
        ClassType thirdPartyClassType = mock(ClassType.class);
        when(thirdPartyMethod.getDeclClassType()).thenReturn(thirdPartyClassType);
        when(thirdPartyClassType.getFullyQualifiedName()).thenReturn("org.apache.http.HttpClient");
        when(thirdPartyMethod.getName()).thenReturn("execute");

        List<MethodSignature> path = Arrays.asList(entryPoint, thirdPartyMethod);
        ThirdPartyPath thirdPartyPath = new ThirdPartyPath(entryPoint, thirdPartyMethod, path, 1);
        String template = TestTemplateGenerator.generateTestTemplate(thirdPartyPath);
        assertNotNull(template);
        assertTrue(template.contains("com.example.service"));
        assertTrue(template.contains("testCreateUser"));
    }

    @Test
    void testGenerateTestTemplate_WithLongerPath() {
        MethodSignature entryPoint = mock(MethodSignature.class);
        ClassType entryClassType = mock(ClassType.class);
        when(entryPoint.getDeclClassType()).thenReturn(entryClassType);
        when(entryClassType.getFullyQualifiedName()).thenReturn("com.example.Controller");
        when(entryPoint.getName()).thenReturn("handleRequest");

        MethodSignature intermediate = mock(MethodSignature.class);
        ClassType intermediateClassType = mock(ClassType.class);
        when(intermediate.getDeclClassType()).thenReturn(intermediateClassType);
        when(intermediateClassType.getFullyQualifiedName()).thenReturn("com.example.Service");
        when(intermediate.getName()).thenReturn("processData");

        MethodSignature thirdPartyMethod = mock(MethodSignature.class);
        ClassType thirdPartyClassType = mock(ClassType.class);
        when(thirdPartyMethod.getDeclClassType()).thenReturn(thirdPartyClassType);
        when(thirdPartyClassType.getFullyQualifiedName()).thenReturn("org.json.JSONObject");
        when(thirdPartyMethod.getName()).thenReturn("toString");

        List<MethodSignature> path = Arrays.asList(entryPoint, intermediate, thirdPartyMethod);
        ThirdPartyPath thirdPartyPath = new ThirdPartyPath(entryPoint, thirdPartyMethod, path, 1);
        String template = TestTemplateGenerator.generateTestTemplate(thirdPartyPath);
        assertNotNull(template);
        assertTrue(template.contains("com.example"));
        assertTrue(template.contains("testHandleRequest"));
    }

    @Test
    void testGenerateTestTemplate_WithInnerClass() {
        MethodSignature entryPoint = mock(MethodSignature.class);
        ClassType entryClassType = mock(ClassType.class);
        when(entryPoint.getDeclClassType()).thenReturn(entryClassType);
        when(entryClassType.getFullyQualifiedName()).thenReturn("com.example.Outer$Inner");
        when(entryPoint.getName()).thenReturn("innerMethod");

        MethodSignature thirdPartyMethod = mock(MethodSignature.class);
        ClassType thirdPartyClassType = mock(ClassType.class);
        when(thirdPartyMethod.getDeclClassType()).thenReturn(thirdPartyClassType);
        when(thirdPartyClassType.getFullyQualifiedName()).thenReturn("java.util.List");
        when(thirdPartyMethod.getName()).thenReturn("add");

        List<MethodSignature> path = Arrays.asList(entryPoint, thirdPartyMethod);
        ThirdPartyPath thirdPartyPath = new ThirdPartyPath(entryPoint, thirdPartyMethod, path, 1);
        String template = TestTemplateGenerator.generateTestTemplate(thirdPartyPath);
        assertNotNull(template);
        assertTrue(template.contains("testInnerMethod"));
    }

    @Test
    void testGenerateTestTemplate_NotEmpty() {
        MethodSignature entryPoint = mock(MethodSignature.class);
        ClassType entryClassType = mock(ClassType.class);
        when(entryPoint.getDeclClassType()).thenReturn(entryClassType);
        when(entryClassType.getFullyQualifiedName()).thenReturn("com.test.TestClass");
        when(entryPoint.getName()).thenReturn("testMethod");

        MethodSignature thirdPartyMethod = mock(MethodSignature.class);
        ClassType thirdPartyClassType = mock(ClassType.class);
        when(thirdPartyMethod.getDeclClassType()).thenReturn(thirdPartyClassType);
        when(thirdPartyClassType.getFullyQualifiedName()).thenReturn("com.library.API");
        when(thirdPartyMethod.getName()).thenReturn("call");

        List<MethodSignature> path = Arrays.asList(entryPoint, thirdPartyMethod);
        ThirdPartyPath thirdPartyPath = new ThirdPartyPath(entryPoint, thirdPartyMethod, path, 1);

        String template = TestTemplateGenerator.generateTestTemplate(thirdPartyPath);
        assertNotNull(template);
        assertFalse(template.isEmpty());
        assertTrue(template.length() > 50); // Should contain substantial template content
    }

    @Test
    void testGenerateTestTemplate_ReplacesPlaceholders() {
        MethodSignature entryPoint = mock(MethodSignature.class);
        ClassType entryClassType = mock(ClassType.class);
        when(entryPoint.getDeclClassType()).thenReturn(entryClassType);
        when(entryClassType.getFullyQualifiedName()).thenReturn("com.app.MyClass");
        when(entryPoint.getName()).thenReturn("myMethod");

        MethodSignature thirdPartyMethod = mock(MethodSignature.class);
        ClassType thirdPartyClassType = mock(ClassType.class);
        when(thirdPartyMethod.getDeclClassType()).thenReturn(thirdPartyClassType);
        when(thirdPartyClassType.getFullyQualifiedName()).thenReturn("com.external.ExternalAPI");
        when(thirdPartyMethod.getName()).thenReturn("apiMethod");

        List<MethodSignature> path = Arrays.asList(entryPoint, thirdPartyMethod);
        ThirdPartyPath thirdPartyPath = new ThirdPartyPath(entryPoint, thirdPartyMethod, path, 1);
        String template = TestTemplateGenerator.generateTestTemplate(thirdPartyPath);
        assertNotNull(template);
        assertFalse(template.contains("packagename"));
        assertFalse(template.contains("TestNameTest"));
        assertTrue(template.contains("testMyMethod") || template.contains("test"));
    }

    @Test
    void testGenerateTestTemplate_ConstructorMethod() {
        MethodSignature entryPoint = mock(MethodSignature.class);
        ClassType entryClassType = mock(ClassType.class);
        when(entryPoint.getDeclClassType()).thenReturn(entryClassType);
        when(entryClassType.getFullyQualifiedName()).thenReturn("com.example.MyClass");
        when(entryPoint.getName()).thenReturn("<init>");

        MethodSignature thirdPartyMethod = mock(MethodSignature.class);
        ClassType thirdPartyClassType = mock(ClassType.class);
        when(thirdPartyMethod.getDeclClassType()).thenReturn(thirdPartyClassType);
        when(thirdPartyClassType.getFullyQualifiedName()).thenReturn("com.library.Library");
        when(thirdPartyMethod.getName()).thenReturn("initialize");

        List<MethodSignature> path = Arrays.asList(entryPoint, thirdPartyMethod);
        ThirdPartyPath thirdPartyPath = new ThirdPartyPath(entryPoint, thirdPartyMethod, path, 1);
        String template = TestTemplateGenerator.generateTestTemplate(thirdPartyPath);
        assertNotNull(template);
        assertTrue(template.contains("com.example"));
    }
}
