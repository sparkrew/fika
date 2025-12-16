package io.github.sparkrew.fika.api_finder.model;

import sootup.core.signatures.MethodSignature;

import java.util.List;

/**
 * Represents a path from a public entry point to a third-party method.
 * This is the internal representation that keeps the MethodSignature objects.
 */
public record ThirdPartyPath(
        MethodSignature entryPoint,
        MethodSignature thirdPartyMethod,
        List<MethodSignature> path
) {
}
