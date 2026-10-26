package io.edgefleet.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.edgefleet.config.Configs;
import io.edgefleet.controller.config.TemplateService;
import io.edgefleet.controller.device.Device;
import io.edgefleet.controller.device.InventoryService;
import io.edgefleet.controller.pki.CertificateAuthority;
import io.edgefleet.controller.rollout.DeploymentService;
import io.edgefleet.sim.DeviceAgent;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Runs the real controller (embedded Postgres, both TLS gRPC listeners) against
 * real simulated devices that claim, open mTLS sessions and apply configs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EdgeFleetIntegrationTest {

    private static final int BOOTSTRAP_PORT = freePort();
    private static final int SESSION_PORT = freePort();
    private static final ScheduledExecutorService TIMERS = Executors.newScheduledThreadPool(2);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        r.add("edgefleet.pki-dir", () -> tempDir("pki"));
        r.add("edgefleet.bootstrap-port", () -> BOOTSTRAP_PORT);
        r.add("edgefleet.session-port", () -> SESSION_PORT);
        r.add("edgefleet.session-target", () -> "localhost:" + SESSION_PORT);
        r.add("edgefleet.dev.embedded-postgres", () -> "true");
        r.add("edgefleet.dev.data-dir", () -> "");
        r.add("edgefleet.rollout-tick", () -> "150ms");
        r.add("edgefleet.dispatch-interval", () -> "100ms");
    }

    private static final String TEMPLATE = """
            {"system": {"hostname": "{{hostname}}", "systemIp": "{{system_ip}}", "siteId": "{{site_id}}",
                        "region": "{{region}}"},
             "transports": [
               {"color": "mpls", "interface": "{{mpls_if}}", "mtu": 1500, "restrict": true},
               {"color": "biz-internet", "interface": "{{inet_if}}", "mtu": %d, "restrict": false}],
             "bfd": {"helloIntervalMs": 1000, "multiplier": %d},
             "ntp": ["10.0.0.10"]}""";

    @Autowired InventoryService inventory;
    @Autowired TemplateService templates;
    @Autowired DeploymentService deployments;
    @Autowired CertificateAuthority ca;
    @Autowired JdbcClient jdbc;
    @LocalServerPort int httpPort;

    private final List<DeviceAgent> agents = new ArrayList<>();

    @AfterEach
    void closeAgents() {
        agents.forEach(DeviceAgent::close);
    }

    @AfterAll
    static void stopTimers() {
        TIMERS.shutdownNow();
    }

    // ---- helpers ---------------------------------------------------------------

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String uniqueGroup() {
        return "g-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static int ipCounter = 1;

    /** Imports n devices into a group; returns serial -> token. */
    private synchronized Map<String, String> importDevices(String group, int n) {
        List<InventoryService.DeviceSpec> specs = new ArrayList<>();
        String prefix = "SN" + group.substring(2).toUpperCase();
        for (int i = 0; i < n; i++) {
            int ip = ipCounter++;
            specs.add(new InventoryService.DeviceSpec(prefix + "-" + i, "C8300", group, "edge-" + ip,
                    "10.20." + (ip / 250) + "." + (ip % 250 + 1), 1000 + ip, "us-west",
                    Map.of("mpls_if", "GigabitEthernet0/0/0", "inet_if", "GigabitEthernet0/0/1")));
        }
        Map<String, String> tokens = new java.util.LinkedHashMap<>();
        inventory.importDevices(specs).forEach(t -> tokens.put(t.serial(), t.bootstrapToken()));
        return tokens;
    }

    private DeviceAgent agent(String serial, String token) {
        return agent(serial, token, DeviceAgent.Behavior.defaults());
    }

    private DeviceAgent agent(String serial, String token, DeviceAgent.Behavior behavior) {
        DeviceAgent a = new DeviceAgent(serial, "C8300", token, ca.caCertificate(), "localhost:" + BOOTSTRAP_PORT,
                behavior, TIMERS);
        agents.add(a);
        return a;
    }

    private List<DeviceAgent> onboard(Map<String, String> tokens) {
        List<DeviceAgent> out = new ArrayList<>();
        tokens.forEach((serial, token) -> {
            DeviceAgent a = agent(serial, token, new DeviceAgent.Behavior(new int[] {10, 60}, 500, 1300, 0));
            a.claim();
            a.connect();
            out.add(a);
        });
        return out;
    }

    private TemplateService.Template template(String name, int inetMtu, int bfdMultiplier) {
        return templates.create(name, Configs.parse(TEMPLATE.formatted(inetMtu, bfdMultiplier)), null);
    }

    private Device device(String serial) {
        return inventory.find(serial).orElseThrow();
    }

    private HttpResponse<String> http(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + httpPort + path))
                .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void setBaseline(String group, TemplateService.Template t) throws Exception {
        assertThat(http("PUT", "/api/groups/" + group + "/baseline",
                "{\"template\":\"" + t.name() + "\",\"version\":" + t.version() + "}").statusCode()).isEqualTo(200);
    }

    // ---- onboarding ------------------------------------------------------------

    @Test
    void zeroTouchOnboardingIssuesIdentityAndDeliversDay0Config() throws Exception {
        String group = uniqueGroup();
        TemplateService.Template v1 = template("branch-" + group, 1500, 7);
        setBaseline(group, v1);
        Map<String, String> tokens = importDevices(group, 3);

        List<DeviceAgent> fleet = onboard(tokens);

        for (DeviceAgent a : fleet) {
            var cert = a.identity().certificate();
            assertThat(CertificateAuthority.deviceSerialOf(cert)).isEqualTo(a.serial());
            assertThat(cert.getIssuerX500Principal()).isEqualTo(ca.caCertificate().getSubjectX500Principal());
            cert.verify(ca.caCertificate().getPublicKey());
            assertThat(cert.getExtendedKeyUsage()).containsExactly("1.3.6.1.5.5.7.3.2"); // clientAuth only
            assertThat(a.identity().systemIp()).isEqualTo(device(a.serial()).systemIp());
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            for (DeviceAgent a : fleet) {
                Device d = device(a.serial());
                assertThat(d.connected()).isTrue();
                assertThat(d.appliedVersion()).isEqualTo(1);
                assertThat(d.inSync()).isTrue();
                assertThat(a.runningConfig().get("system").get("hostname").asString()).isEqualTo(d.hostname());
            }
        });
    }

    @Test
    void bootstrapTokenIsCheckedAndSpentOnUse() {
        String group = uniqueGroup();
        Map<String, String> tokens = importDevices(group, 1);
        String serial = tokens.keySet().iterator().next();

        assertThatThrownBy(() -> agent(serial, "0".repeat(40)).claim())
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));

        // A CSR naming another serial is refused, and the token survives the refusal.
        assertThatThrownBy(() -> agent("SOMEONE-ELSE", tokens.get(serial)).claim())
                .isInstanceOf(StatusRuntimeException.class);
        assertThat(device(serial).state()).isEqualTo("ALLOWED");

        agent(serial, tokens.get(serial)).claim();
        assertThat(device(serial).state()).isEqualTo("ONBOARDED");

        assertThatThrownBy(() -> agent(serial, tokens.get(serial)).claim())
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    void decommissionedDeviceIsDisconnectedAndCannotReconnect() throws Exception {
        String group = uniqueGroup();
        DeviceAgent a = onboard(importDevices(group, 1)).getFirst();
        await().atMost(Duration.ofSeconds(10)).until(() -> device(a.serial()).connected());

        assertThat(http("POST", "/api/devices/" + a.serial() + "/decommission", null).statusCode()).isEqualTo(204);
        await().atMost(Duration.ofSeconds(10)).until(() -> !a.sessionOpen());

        a.connect();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(a.sessionError()).isInstanceOf(StatusRuntimeException.class);
            assertThat(((StatusRuntimeException) a.sessionError()).getStatus().getCode())
                    .isEqualTo(Status.Code.PERMISSION_DENIED);
        });
    }

    // ---- rollouts --------------------------------------------------------------

    @Test
    void stagedRolloutReachesEveryDeviceAndBecomesTheBaseline() throws Exception {
        String group = uniqueGroup();
        String name = "branch-" + group;
        TemplateService.Template v1 = template(name, 1500, 7);
        setBaseline(group, v1);
        List<DeviceAgent> fleet = onboard(importDevices(group, 20));
        await().atMost(Duration.ofSeconds(20)).until(() -> fleet.stream().allMatch(a -> device(a.serial()).inSync()));

        TemplateService.Template v2 = template(name, 1500, 5);
        DeploymentService.Deployment d = deployments.create(new DeploymentService.CreateRequest(
                name, v2.version(), group, List.of(10, 50, 100), 10, 10));

        await().atMost(Duration.ofSeconds(40)).until(() -> deployments.get(d.id()).orElseThrow().state().equals("COMPLETED"));
        DeploymentService.Status status = deployments.status(d.id()).orElseThrow();
        assertThat(status.batches()).extracting(DeploymentService.BatchStatus::targets).containsExactly(2, 8, 10);
        assertThat(status.batches()).allSatisfy(b -> assertThat(b.byState()).containsOnlyKeys("SUCCEEDED"));
        for (DeviceAgent a : fleet) {
            assertThat(a.runningConfig().get("bfd").get("multiplier").asInt()).isEqualTo(5);
            assertThat(device(a.serial()).inSync()).isTrue();
        }
        assertThat(jdbc.sql("SELECT template_version FROM group_baselines WHERE device_group = ?")
                .param(group).query(Integer.class).single()).isEqualTo(v2.version());
    }

    @Test
    void badConfigFailsTheCanaryAndIsRolledBackBeforeReachingTheFleet() {
        String group = uniqueGroup();
        String name = "branch-" + group;
        TemplateService.Template v1 = template(name, 1500, 7);
        try {
            setBaseline(group, v1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        List<DeviceAgent> fleet = onboard(importDevices(group, 20));
        await().atMost(Duration.ofSeconds(20)).until(() -> fleet.stream().allMatch(a -> device(a.serial()).inSync()));
        Map<String, String> goodHashes = new java.util.HashMap<>();
        fleet.forEach(a -> goodHashes.put(a.serial(), a.runningHash()));

        // Valid per the validator, but an MTU of 1200 breaks the IPsec tunnels on the device.
        TemplateService.Template bad = template(name, 1200, 7);
        DeploymentService.Deployment d = deployments.create(new DeploymentService.CreateRequest(
                name, bad.version(), group, List.of(10, 50, 100), 10, 10));

        await().atMost(Duration.ofSeconds(40)).until(() -> deployments.get(d.id()).orElseThrow().state().equals("ROLLED_BACK"));
        DeploymentService.Status status = deployments.status(d.id()).orElseThrow();
        assertThat(status.deployment().reason()).contains("batch 1").contains("failed");
        assertThat(status.batches().get(1).byState()).containsOnlyKeys("PENDING");
        assertThat(status.batches().get(2).byState()).containsOnlyKeys("PENDING");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            for (DeviceAgent a : fleet) {
                assertThat(a.runningConfig().get("transports").get(1).get("mtu").asInt()).isEqualTo(1500);
                assertThat(a.runningHash()).isEqualTo(goodHashes.get(a.serial()));
                assertThat(device(a.serial()).inSync()).isTrue();
            }
        });
        List<String> kinds = jdbc.sql("SELECT kind FROM events WHERE deployment_id = ? ORDER BY id")
                .param(d.id()).query(String.class).list();
        assertThat(kinds).contains("deployment.halted", "deployment.rolled_back").doesNotContain("deployment.completed");
    }

    @Test
    void preflightRefusesADeploymentThatCannotRenderForEveryDevice() throws Exception {
        String group = uniqueGroup();
        onboard(importDevices(group, 2));
        templates.create("needs-var-" + group, Configs.parse("""
                {"system": {"hostname": "{{hostname}}", "systemIp": "{{system_ip}}", "siteId": "{{site_id}}"},
                 "transports": [{"color": "lte", "interface": "{{lte_if}}", "mtu": 1500}]}"""), null);

        HttpResponse<String> r = http("POST", "/api/deployments", """
                {"template": "needs-var-%s", "version": 1, "group": "%s", "batchPercents": [100],
                 "maxFailurePct": 0, "healthTimeoutSec": 10}""".formatted(group, group));
        assertThat(r.statusCode()).isEqualTo(422);
        JsonNode body = Configs.parse(r.body());
        assertThat(body.get("failures").size()).isEqualTo(2);
        assertThat(body.get("failures").values().iterator().next().get(0).asString()).contains("lte_if");
        assertThat(deployments.list(500)).noneMatch(x -> x.group().equals(group));
    }

    // ---- reconciliation --------------------------------------------------------

    @Test
    void driftIsDetectedAndTheIntendedConfigIsRestored() throws Exception {
        String group = uniqueGroup();
        setBaseline(group, template("branch-" + group, 1500, 7));
        DeviceAgent a = onboard(importDevices(group, 1)).getFirst();
        await().atMost(Duration.ofSeconds(10)).until(() -> device(a.serial()).inSync());
        long before = device(a.serial()).appliedVersion();

        a.editLocally("edited on the CLI");
        a.sendHeartbeat();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Device d = device(a.serial());
            assertThat(d.appliedVersion()).isGreaterThan(before);
            assertThat(d.inSync()).isTrue();
            assertThat(a.runningConfig().has("banner")).isFalse();
        });
        List<String> kinds = jdbc.sql("SELECT kind FROM events WHERE serial = ? ORDER BY id")
                .param(a.serial()).query(String.class).list();
        assertThat(kinds).containsSubsequence("config.drift_detected", "config.assigned", "config.drift_cleared");
    }

    @Test
    void deviceOfflineDuringRolloutIsDeferredAndConvergesOnReconnect() throws Exception {
        String group = uniqueGroup();
        String name = "branch-" + group;
        setBaseline(group, template(name, 1500, 7));
        List<DeviceAgent> fleet = onboard(importDevices(group, 4));
        await().atMost(Duration.ofSeconds(15)).until(() -> fleet.stream().allMatch(a -> device(a.serial()).inSync()));

        DeviceAgent offline = fleet.getFirst();
        offline.disconnect();
        await().atMost(Duration.ofSeconds(10)).until(() -> !device(offline.serial()).connected());

        TemplateService.Template v2 = template(name, 1500, 4);
        DeploymentService.Deployment d = deployments.create(new DeploymentService.CreateRequest(
                name, v2.version(), group, List.of(100), 0, 10));
        await().atMost(Duration.ofSeconds(30)).until(() -> deployments.get(d.id()).orElseThrow().state().equals("COMPLETED"));
        assertThat(deployments.status(d.id()).orElseThrow().batches().getFirst().byState())
                .containsEntry("DEFERRED", 1).containsEntry("SUCCEEDED", 3);

        offline.connect();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(offline.runningConfig().get("bfd").get("multiplier").asInt()).isEqualTo(4);
            assertThat(device(offline.serial()).inSync()).isTrue();
        });
    }
}
