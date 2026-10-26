package io.edgefleet.config;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Renders a config template (a JSON document with {@code {{name}}} placeholders)
 * with one device's variables.
 *
 * <ul>
 *   <li>A string that is exactly {@code "{{name}}"} is replaced by the variable's JSON
 *       value of any type, so a template can insert a number, a list of transports or
 *       a whole object.</li>
 *   <li>A placeholder inside a longer string is interpolated as text:
 *       {@code "branch-{{site_id}}"} becomes {@code "branch-117"}.</li>
 * </ul>
 *
 * Rendering is strict: every missing variable is reported at once, and nothing is
 * rendered with a gap in it. A config that silently lost a value is worse than no
 * config at all.
 */
public final class TemplateRenderer {

    private static final Pattern WHOLE = Pattern.compile("^\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*}}$");
    private static final Pattern INNER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*}}");

    /** Thrown when variables the template uses are not set for this device. */
    public static final class MissingVariablesException extends RuntimeException {
        private final Set<String> missing;

        MissingVariablesException(Set<String> missing) {
            super("missing variables: " + String.join(", ", missing));
            this.missing = Set.copyOf(missing);
        }

        public Set<String> missing() {
            return missing;
        }
    }

    private TemplateRenderer() {
    }

    public static JsonNode render(JsonNode template, Map<String, JsonNode> variables) {
        Set<String> missing = new LinkedHashSet<>();
        JsonNode out = renderNode(template, variables, missing);
        if (!missing.isEmpty()) {
            throw new MissingVariablesException(missing);
        }
        return out;
    }

    /** Variable names the template references, in order of first use. */
    public static Set<String> referencedVariables(JsonNode template) {
        Set<String> names = new LinkedHashSet<>();
        collect(template, names);
        return names;
    }

    private static JsonNode renderNode(JsonNode node, Map<String, JsonNode> vars, Set<String> missing) {
        if (node.isObject()) {
            ObjectNode out = Configs.JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                out.set(e.getKey(), renderNode(e.getValue(), vars, missing));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = Configs.JSON.createArrayNode();
            for (Iterator<JsonNode> it = node.values().iterator(); it.hasNext(); ) {
                out.add(renderNode(it.next(), vars, missing));
            }
            return out;
        }
        if (node.isString()) {
            String text = node.asString();
            Matcher whole = WHOLE.matcher(text);
            if (whole.matches()) {
                JsonNode value = vars.get(whole.group(1));
                if (value == null) {
                    missing.add(whole.group(1));
                    return node;
                }
                return value.deepCopy();
            }
            Matcher inner = INNER.matcher(text);
            StringBuilder sb = new StringBuilder();
            boolean replaced = false;
            while (inner.find()) {
                JsonNode value = vars.get(inner.group(1));
                if (value == null) {
                    missing.add(inner.group(1));
                    inner.appendReplacement(sb, Matcher.quoteReplacement(inner.group()));
                } else {
                    String s = value.isString() ? value.asString() : Configs.canonical(value);
                    inner.appendReplacement(sb, Matcher.quoteReplacement(s));
                    replaced = true;
                }
            }
            inner.appendTail(sb);
            return replaced ? StringNode.valueOf(sb.toString()) : node;
        }
        return node;
    }

    private static void collect(JsonNode node, Set<String> names) {
        if (node.isObject() || node.isArray()) {
            node.values().forEach(child -> collect(child, names));
        } else if (node.isString()) {
            Matcher m = INNER.matcher(node.asString());
            while (m.find()) {
                names.add(m.group(1));
            }
        }
    }
}
