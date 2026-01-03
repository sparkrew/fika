package io.github.sparkrew.fika.api_finder.model;

import java.util.List;
import java.util.Set;

/**
 * Data class to hold class members (constructors, setters, getters) and their required imports.
 */
public record ClassMemberData(
        List<String> constructors, 
        List<String> setters, 
        List<String> getters,
        Set<String> imports) {
}
