package io.github.sparkrew.fika.api_finder.model;

import sootup.core.signatures.MethodSignature;

/**
 * Helper record to store a method and its path length during traversal
 */
public record PathNode(MethodSignature method, int pathLength) {}