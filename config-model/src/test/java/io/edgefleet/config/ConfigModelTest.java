package io.edgefleet.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ConfigModelTest {

    private static final String TEMPLATE = """
            {
              "system": {"hostname": "branch-{{site_id}}", "systemIp": "{{system_ip}}",
                         "siteId": "{{site_id}}", "region": "{{region}}"},
              "transports": "{{transports}}",
              "bfd": {"helloIntervalMs": 1000, "multiplier": 7},
              "ntp": ["10.0.0.10", "10.0.0.11"]
            }""";

    private static Map<String, JsonNode> vars() {
        return Map.of(
                "site_id", Configs.parse("117"),
                "system_ip", Configs.parse("\"10.1.0.18\""),
                "region", Configs.parse("\"us-west\""),
                "transports", Configs.parse("""
                        [{"color":"mpls","interface":"GigabitEthernet0/0/0","mtu":1500,"restrict":true},
                         {"color":"biz-internet","interface":"GigabitEthernet0/0/1","mtu":1500,"restrict":false}]"""));
    }

    @Test
    void hashIgnoresKeyOrderAndWhitespaceButNotValues() {
        String a = Configs.hash("{\"b\": 1, \"a\": {\"y\": [1,2], \"x\": \"s\"}}");
        String b = Configs.hash("{\"a\":{\"x\":\"s\",\"y\":[1,2]},\"b\":1}");
        String c = Configs.hash("{\"a\":{\"x\":\"s\",\"y\":[2,1]},\"b\":1}");
        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(a).isNotEqualTo(c); // list order is significant
    }

    @Test
    void rendersWholeValuesWithTheirTypeAndInterpolatesInsideStrings() {
        JsonNode cfg = TemplateRenderer.render(Configs.parse(TEMPLATE), vars());
        assertThat(cfg.get("system").get("hostname").asString()).isEqualTo("branch-117");
        assertThat(cfg.get("system").get("siteId").isIntegralNumber()).isTrue();
        assertThat(cfg.get("system").get("siteId").asInt()).isEqualTo(117);
        assertThat(cfg.get("transports").size()).isEqualTo(2);
        assertThat(ConfigValidator.validate(cfg)).isEmpty();
    }

    @Test
    void reportsEveryMissingVariableAtOnce() {
        assertThatThrownBy(() -> TemplateRenderer.render(Configs.parse(TEMPLATE),
                Map.of("site_id", Configs.parse("117"))))
                .isInstanceOf(TemplateRenderer.MissingVariablesException.class)
                .satisfies(e -> assertThat(((TemplateRenderer.MissingVariablesException) e).missing())
                        .containsExactlyInAnyOrder("system_ip", "region", "transports"));
        assertThat(TemplateRenderer.referencedVariables(Configs.parse(TEMPLATE)))
                .containsExactly("site_id", "system_ip", "region", "transports");
    }

    @Test
    void validatorCatchesConfigsThatWouldBreakTheDevice() {
        JsonNode bad = Configs.parse("""
                {"system": {"hostname": "bad_host!", "systemIp": "10.1.0.300", "siteId": 0},
                 "transports": [
                   {"color": "mpls", "interface": "GigabitEthernet0/0/0", "mtu": 1500},
                   {"color": "mpls", "interface": "GigabitEthernet0/0/0", "mtu": 20000},
                   {"color": "gold", "interface": "eth", "mtu": 1500}],
                 "bfd": {"helloIntervalMs": 100, "multiplier": 2},
                 "ntp": ["time.example.com"]}""");
        assertThat(ConfigValidator.validate(bad)).extracting(ConfigValidator.Violation::path).containsExactly(
                "system.hostname", "system.systemIp", "system.siteId",
                "transports[1].color", "transports[1].interface", "transports[1].mtu",
                "transports[2].color", "transports[2].interface",
                "bfd", "ntp[0]");
    }

    @Test
    void diffMatchesTransportsByColorSoReorderingIsNotAChange() {
        JsonNode v1 = TemplateRenderer.render(Configs.parse(TEMPLATE), vars());
        JsonNode reordered = Configs.parse(Configs.canonical(v1));
        var arr = (tools.jackson.databind.node.ArrayNode) reordered.get("transports");
        JsonNode first = arr.remove(0);
        arr.add(first);
        assertThat(ConfigDiff.diff(v1, reordered)).isEmpty();

        JsonNode v2 = Configs.parse(Configs.canonical(v1));
        ((tools.jackson.databind.node.ObjectNode) v2.get("transports").get(1)).put("mtu", 1400);
        ((tools.jackson.databind.node.ObjectNode) v2.get("bfd")).put("multiplier", 5);
        ((tools.jackson.databind.node.ObjectNode) v2).remove("ntp");
        assertThat(ConfigDiff.diff(v1, v2)).extracting(Object::toString).containsExactly(
                "~ bfd.multiplier: 7 -> 5",
                "- ntp (was [\"10.0.0.10\",\"10.0.0.11\"])",
                "~ transports[color=biz-internet].mtu: 1500 -> 1400");
    }
}
