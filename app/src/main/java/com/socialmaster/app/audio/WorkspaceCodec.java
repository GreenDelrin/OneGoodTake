package com.socialmaster.app.audio;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Encodes the small local workspace snapshot without a new serialization dependency. */
public final class WorkspaceCodec {
    private WorkspaceCodec() { }

    public static String encode(Map<String, String> values) {
        Properties properties = new Properties();
        properties.putAll(new TreeMap<>(values));
        StringWriter writer = new StringWriter();
        try {
            properties.store(writer, null);
            return writer.toString();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static Map<String, String> decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return Map.of();
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(encoded));
            Map<String, String> result = new TreeMap<>();
            for (String key : properties.stringPropertyNames()) result.put(key, properties.getProperty(key));
            return Collections.unmodifiableMap(result);
        } catch (IOException | IllegalArgumentException malformed) {
            return Map.of();
        }
    }
}
