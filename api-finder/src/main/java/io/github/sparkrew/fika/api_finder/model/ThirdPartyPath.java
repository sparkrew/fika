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
        List<MethodSignature> path,
        Integer callCount // This is the number of the third party method call sites within the same method. We record
        // because we do not keep duplicate records when the same path is there for the same third party method even if
        // it is called multiple times inside one method. Note that this does not count how many times the third party
        // method is actually called. For example, we do not keep track of the number of times the method is called when
        // there is a loop. We simply record how many call sites there are.
) {
}
