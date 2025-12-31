package io.github.sparkrew.fika.api_finder.model;

import org.junit.jupiter.api.Test;
import sootup.core.signatures.MethodSignature;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for PathNode record.
 */
class PathNodeTest {

    @Test
    void testPathNodeCreation() {
        MethodSignature methodSig = mock(MethodSignature.class);
        int pathLength = 5;
        PathNode pathNode = new PathNode(methodSig, pathLength);
        assertNotNull(pathNode);
        assertEquals(methodSig, pathNode.method());
        assertEquals(pathLength, pathNode.pathLength());
    }

    @Test
    void testPathNodeEquality() {
        MethodSignature methodSig = mock(MethodSignature.class);
        PathNode node1 = new PathNode(methodSig, 5);
        PathNode node2 = new PathNode(methodSig, 5);
        assertEquals(node1, node2);
        assertEquals(node1.hashCode(), node2.hashCode());
    }

    @Test
    void testPathNodeWithDifferentPathLengths() {
        MethodSignature methodSig = mock(MethodSignature.class);
        PathNode node1 = new PathNode(methodSig, 5);
        PathNode node2 = new PathNode(methodSig, 10);
        assertNotEquals(node1, node2);
    }

    @Test
    void testPathNodeWithZeroLength() {
        MethodSignature methodSig = mock(MethodSignature.class);
        PathNode pathNode = new PathNode(methodSig, 0);
        assertNotNull(pathNode);
        assertEquals(0, pathNode.pathLength());
    }

    @Test
    void testPathNodeToString() {
        MethodSignature methodSig = mock(MethodSignature.class);
        PathNode pathNode = new PathNode(methodSig, 5);
        String result = pathNode.toString();
        assertNotNull(result);
        assertTrue(result.contains("PathNode"));
    }
}
