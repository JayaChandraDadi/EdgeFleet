package io.edgefleet.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Checks a rendered device config before it is ever pushed. Every rule protects
 * against a config that would be accepted by the device but break it: a duplicated
 * transport color, an MTU outside what the interface supports, a BFD timer so
 * aggressive it flaps the tunnels.
 *
 * <p>Config shape:
 * <pre>{
 *   "system":     {"hostname": "...", "systemIp": "10.1.0.12", "siteId": 117, "region": "us-west"},
 *   "transports": [{"color": "mpls", "interface": "Gi0/0/0", "mtu": 1500, "restrict": true}],
 *   "bfd":        {"helloIntervalMs": 1000, "multiplier": 7},
 *   "ntp":        ["10.0.0.10"],
 *   "banner":     "..."
 * }</pre>
 */
public final class ConfigValidator {

    /** A rule violation at a JSON path such as {@code transports[1].mtu}. */
    public record Violation(String path, String message) {
        @Override
        public String toString() {
            return path + ": " + message;
        }
    }

    public static final Set<String> KNOWN_COLORS = Set.of(
            "mpls", "biz-internet", "public-internet", "lte", "metro-ethernet", "private1", "private2");

    private static final Pattern HOSTNAME = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$");
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern INTERFACE = Pattern.compile("^[A-Za-z][A-Za-z-]*\\d+(/\\d+){0,3}(\\.\\d+)?$");

    private ConfigValidator() {
    }

    public static List<Violation> validate(JsonNode config) {
        List<Violation> v = new ArrayList<>();
        if (!config.isObject()) {
            v.add(new Violation("$", "config must be a JSON object"));
            return v;
        }

        JsonNode system = config.get("system");
        if (system == null || !system.isObject()) {
            v.add(new Violation("system", "required object"));
        } else {
            text(system, "hostname", "system.hostname", HOSTNAME, "must be a valid hostname (letters, digits, '-')", v);
            text(system, "systemIp", "system.systemIp", IPV4, "must be an IPv4 address", v);
            JsonNode site = system.get("siteId");
            if (site == null || !site.isIntegralNumber() || site.asLong() < 1 || site.asLong() > 4_294_967_295L) {
                v.add(new Violation("system.siteId", "must be an integer from 1 to 4294967295"));
            }
        }

        JsonNode transports = config.get("transports");
        if (transports == null || !transports.isArray() || transports.isEmpty()) {
            v.add(new Violation("transports", "at least one transport is required"));
        } else {
            Set<String> colors = new HashSet<>();
            Set<String> interfaces = new HashSet<>();
            for (int i = 0; i < transports.size(); i++) {
                JsonNode t = transports.get(i);
                String p = "transports[" + i + "]";
                if (!t.isObject()) {
                    v.add(new Violation(p, "must be an object"));
                    continue;
                }
                JsonNode color = t.get("color");
                if (color == null || !KNOWN_COLORS.contains(color.asString(""))) {
                    v.add(new Violation(p + ".color", "must be one of " + new java.util.TreeSet<>(KNOWN_COLORS)));
                } else if (!colors.add(color.asString())) {
                    v.add(new Violation(p + ".color", "color '" + color.asString() + "' is used twice"));
                }
                JsonNode ifName = t.get("interface");
                if (ifName == null || !INTERFACE.matcher(ifName.asString("")).matches()) {
                    v.add(new Violation(p + ".interface", "must be an interface name such as GigabitEthernet0/0/1"));
                } else if (!interfaces.add(ifName.asString())) {
                    v.add(new Violation(p + ".interface", "interface '" + ifName.asString() + "' is used twice"));
                }
                JsonNode mtu = t.get("mtu");
                if (mtu == null || !mtu.isIntegralNumber() || mtu.asInt() < 576 || mtu.asInt() > 9216) {
                    v.add(new Violation(p + ".mtu", "must be an integer from 576 to 9216"));
                }
            }
        }

        JsonNode bfd = config.get("bfd");
        if (bfd != null) {
            JsonNode hello = bfd.get("helloIntervalMs");
            if (hello == null || !hello.isIntegralNumber() || hello.asInt() < 100 || hello.asInt() > 300_000) {
                v.add(new Violation("bfd.helloIntervalMs", "must be an integer from 100 to 300000"));
            }
            JsonNode mult = bfd.get("multiplier");
            if (mult == null || !mult.isIntegralNumber() || mult.asInt() < 1 || mult.asInt() > 20) {
                v.add(new Violation("bfd.multiplier", "must be an integer from 1 to 20"));
            }
            if (hello != null && mult != null && hello.isIntegralNumber() && mult.isIntegralNumber()
                    && hello.asInt() * mult.asInt() < 300) {
                v.add(new Violation("bfd", "detection time (hello x multiplier) under 300 ms flaps tunnels on jittery links"));
            }
        }

        JsonNode ntp = config.get("ntp");
        if (ntp != null) {
            if (!ntp.isArray()) {
                v.add(new Violation("ntp", "must be a list of IPv4 addresses"));
            } else {
                for (int i = 0; i < ntp.size(); i++) {
                    if (!IPV4.matcher(ntp.get(i).asString("")).matches()) {
                        v.add(new Violation("ntp[" + i + "]", "must be an IPv4 address"));
                    }
                }
            }
        }
        return v;
    }

    private static void text(JsonNode obj, String field, String path, Pattern pattern, String message,
                             List<Violation> v) {
        JsonNode n = obj.get(field);
        if (n == null || !n.isString() || !pattern.matcher(n.asString()).matches()) {
            v.add(new Violation(path, message));
        }
    }
}
