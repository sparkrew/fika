package io.github.sparkrew.fika.api_finder.model;

import org.junit.jupiter.api.Test;
import sootup.core.signatures.MethodSignature;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for ThirdPartyPath record.
 */
class ThirdPartyPathTest {

    @Test
    void testThirdPartyPathCreation() {
        MethodSignature entryPoint = mock(MethodSignature.class);
        MethodSignature thirdParty = mock(MethodSignature.class);
        List<MethodSignature> path = Arrays.asList(entryPoint, thirdParty);
        Integer callCount = 3;
        ThirdPartyPath thirdPartyPath = new ThirdPartyPath(entryPoint, thirdParty, path, callCount);
        assertNotNull(thirdPartyPath);
        assertEquals(entryPoint, thirdPartyPath.entryPoint());
        assertEquals(thirdParty, thirdPartyPath.thirdPartyMethod());
        assertEquals(path, thirdPartyPath.path());
        assertEquals(2, thirdPartyPath.path().size());
        assertEquals(callCount, thirdPartyPath.callCount());
    }

    @Test
    void testThirdPartyPathWithEmptyPath() {
        MethodSignature entryPoint = mock(MethodSignature.class);
        MethodSignature thirdParty = mock(MethodSignature.class);
        List<MethodSignature> emptyPath = Collections.emptyList();
        ThirdPartyPath thirdPartyPath = new ThirdPartyPath(entryPoint, thirdParty, emptyPath, 1);
        assertNotNull(thirdPartyPath);
        assertTrue(thirdPartyPath.path().isEmpty());
    }

    @Test
    void testThirdPartyPathEquality() {
        MethodSignature entryPoint = mock(MethodSignature.class);
        MethodSignature thirdParty = mock(MethodSignature.class);
        List<MethodSignature> path = Arrays.asList(entryPoint, thirdParty);
        ThirdPartyPath path1 = new ThirdPartyPath(entryPoint, thirdParty, path, 1);
        ThirdPartyPath path2 = new ThirdPartyPath(entryPoint, thirdParty, path, 1);
        assertEquals(path1, path2);
        assertEquals(path1.hashCode(), path2.hashCode());
    }

    @Test
    void testThirdPartyPathToString() {
        MethodSignature entryPoint = mock(MethodSignature.class);
        MethodSignature thirdParty = mock(MethodSignature.class);
        List<MethodSignature> path = Arrays.asList(entryPoint, thirdParty);
        ThirdPartyPath thirdPartyPath = new ThirdPartyPath(entryPoint, thirdParty, path, 2);
        String result = thirdPartyPath.toString();
        assertNotNull(result);
        assertTrue(result.contains("ThirdPartyPath"));
    }

    @Test
    void testThirdPartyPathWithLongPath() {
        MethodSignature entryPoint = mock(MethodSignature.class);
        MethodSignature intermediate = mock(MethodSignature.class);
        MethodSignature thirdParty = mock(MethodSignature.class);
        List<MethodSignature> path = Arrays.asList(entryPoint, intermediate, thirdParty);
        ThirdPartyPath thirdPartyPath = new ThirdPartyPath(entryPoint, thirdParty, path, 1);
        assertEquals(3, thirdPartyPath.path().size());
        assertEquals(intermediate, thirdPartyPath.path().get(1));
    }

    @Test
    void testThirdPartyPathWithMultipleCallSites() {
        MethodSignature entryPoint = mock(MethodSignature.class);
        MethodSignature thirdParty = mock(MethodSignature.class);
        List<MethodSignature> path = Arrays.asList(entryPoint, thirdParty);
        Integer callCount = 5;
        ThirdPartyPath thirdPartyPath = new ThirdPartyPath(entryPoint, thirdParty, path, callCount);
        assertEquals(5, thirdPartyPath.callCount());
    }
}
