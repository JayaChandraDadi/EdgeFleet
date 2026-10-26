package io.edgefleet.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Structural diff between two config documents, reported as JSON paths. Arrays of
 * objects that carry a {@code color} (transports) are matched by color rather than
 * by position, so reordering transports is not a change and editing one transport
 * shows up as an edit to that transport, not as a shuffle of the whole list.
 */
public final class ConfigDiff {

    public enum Op { ADDED, REMOVED, CHANGED }

    /** One difference; {@code from}/{@code to} are canonical JSON, or null when absent. */
    public record Change(String path, Op op, String from, String to) {
        @Override
        public String toString() {
            return switch (op) {
                case ADDED -> "+ " + path + " = " + to;
                case REMOVED -> "- " + path + " (was " + from + ")";
                case CHANGED -> "~ " + path + ": " + from + " -> " + to;
            };
        }
    }

    private ConfigDiff() {
    }

    public static List<Change> diff(JsonNode from, JsonNode to) {
        List<Change> out = new ArrayList<>();
        walk("", from, to, out);
        return out;
    }

    private static void walk(String path, JsonNode a, JsonNode b, List<Change> out) {
        if (a == null && b == null) {
            return;
        }
        if (a == null) {
            out.add(new Change(path(path), Op.ADDED, null, Configs.canonical(b)));
            return;
        }
        if (b == null) {
            out.add(new Change(path(path), Op.REMOVED, Configs.canonical(a), null));
            return;
        }
        if (a.isObject() && b.isObject()) {
            Set<String> keys = new TreeSet<>(a.propertyNames());
            keys.addAll(b.propertyNames());
            for (String k : keys) {
                walk(path.isEmpty() ? k : path + "." + k, a.get(k), b.get(k), out);
            }
            return;
        }
        if (a.isArray() && b.isArray() && keyedByColor(a) && keyedByColor(b)) {
            Set<String> colors = new TreeSet<>();
            a.values().forEach(n -> colors.add(n.get("color").asString()));
            b.values().forEach(n -> colors.add(n.get("color").asString()));
            for (String c : colors) {
                walk(path + "[color=" + c + "]", byColor(a, c), byColor(b, c), out);
            }
            return;
        }
        if (a.isArray() && b.isArray()) {
            int n = Math.max(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                walk(path + "[" + i + "]", i < a.size() ? a.get(i) : null, i < b.size() ? b.get(i) : null, out);
            }
            return;
        }
        String ca = Configs.canonical(a);
        String cb = Configs.canonical(b);
        if (!ca.equals(cb)) {
            out.add(new Change(path(path), Op.CHANGED, ca, cb));
        }
    }

    private static boolean keyedByColor(JsonNode array) {
        if (array.isEmpty()) {
            return true;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode n : array.values()) {
            if (!n.isObject() || n.get("color") == null || !seen.add(n.get("color").asString())) {
                return false;
            }
        }
        return true;
    }

    private static JsonNode byColor(JsonNode array, String color) {
        for (JsonNode n : array.values()) {
            if (color.equals(n.get("color").asString())) {
                return n;
            }
        }
        return null;
    }

    private static String path(String p) {
        return p.isEmpty() ? "$" : p;
    }
}
