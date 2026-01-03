package io.github.sparkrew.fika.api_finder.model;

import java.util.List;
import java.util.Set;

/**
 * Data class to hold class members (constructors, setters) and their required imports.
 */
public record ClassMemberData(
        List<String> constructors, 
        List<String> setters, 
        Set<String> imports) {
}
