package io.edgefleet.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parsing, canonical form and hashing of device config documents.
 *
 * <p>The controller and every device hash configs the same way, so a device can
 * report just the hash of what it is running and the controller can tell whether
 * that is exactly what it intended. The canonical form sorts object keys at every
 * level and has no insignificant whitespace, so two documents that differ only in
 * key order or formatting hash the same.
 */
public final class Configs {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private Configs() {
    }

    public static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** Canonical JSON: sorted keys, no whitespace. */
    public static String canonical(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(node, sb);
        return sb.toString();
    }

    /** SHA-256 of the canonical form, hex encoded. */
    public static String hash(JsonNode node) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(node).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hash(String json) {
        return hash(parse(json));
    }

    private static void writeCanonical(JsonNode node, StringBuilder sb) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames());
            names.sort(null);
            sb.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(JSON.writeValueAsString(names.get(i))).append(':');
                writeCanonical(node.get(names.get(i)), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeCanonical(node.get(i), sb);
            }
            sb.append(']');
        } else {
            sb.append(JSON.writeValueAsString(node));
        }
    }
}
