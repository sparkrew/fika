package io.github.sparkrew.fika.api_finder.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CoverageLogger {

    private static final File coverageFile = new File("coverage.json");
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Set<String> loggedPairs = Collections.synchronizedSet(new HashSet<>());

    public static synchronized void logCoverage(String caller, String thirdPartyMethod, boolean isCovered) {
        try {
            List<Map<String, Object>> entries = new ArrayList<>();
            // If file exists, read existing entries
            if (coverageFile.exists()) {
                try {
                    entries = mapper.readValue(coverageFile, new TypeReference<List<Map<String, Object>>>() {
                    });
                    for (Map<String, Object> entry : entries) {
                        String callerKey = (String) entry.get("caller");
                        String methodKey = (String) entry.get("thirdPartyMethod");
                        loggedPairs.add(callerKey + "->" + methodKey);
                    }
                } catch (Exception ignored) {
                }
            }
            String pairKey = caller + "->" + thirdPartyMethod;
            // Add only if not already logged
            if (loggedPairs.add(pairKey)) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("caller", caller);
                entry.put("thirdPartyMethod", thirdPartyMethod);
                entry.put("covered", isCovered);
                entries.add(entry);

                mapper.writeValue(coverageFile, entries);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
