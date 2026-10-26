package io.edgefleet.controller.api;

import io.edgefleet.config.ConfigDiff;
import io.edgefleet.config.Configs;
import io.edgefleet.controller.config.ConfigService;
import io.edgefleet.controller.config.TemplateService;
import io.edgefleet.controller.device.Device;
import io.edgefleet.controller.device.InventoryService;
import io.edgefleet.controller.pki.CertificateAuthority;
import io.edgefleet.controller.rollout.DeploymentService;
import io.edgefleet.controller.session.SessionRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class ApiController {

    private final InventoryService inventory;
    private final TemplateService templates;
    private final ConfigService configs;
    private final DeploymentService deployments;
    private final JdbcClient jdbc;
    private final CertificateAuthority ca;
    private final SessionRegistry sessions;

    public ApiController(InventoryService inventory, TemplateService templates, ConfigService configs,
                         DeploymentService deployments, JdbcClient jdbc, CertificateAuthority ca,
                         SessionRegistry sessions) {
        this.inventory = inventory;
        this.templates = templates;
        this.configs = configs;
        this.deployments = deployments;
        this.jdbc = jdbc;
        this.ca = ca;
        this.sessions = sessions;
    }

    // ---- inventory and devices -------------------------------------------------

    /** Imports a provisioning file. Bootstrap tokens are returned only here, once. */
    @PostMapping("/inventory")
    @ResponseStatus(HttpStatus.CREATED)
    public List<InventoryService.Imported> importInventory(@RequestBody List<InventoryService.DeviceSpec> devices) {
        return inventory.importDevices(devices);
    }

    @GetMapping("/devices")
    public List<Device> devices(@RequestParam(required = false) String group,
                                @RequestParam(required = false) String state,
                                @RequestParam(defaultValue = "500") int limit,
                                @RequestParam(defaultValue = "0") int offset) {
        return inventory.list(group, state, Math.min(Math.max(limit, 1), 5000), Math.max(offset, 0));
    }

    @GetMapping("/devices/{serial}")
    public ResponseEntity<Map<String, Object>> device(@PathVariable String serial) {
        return inventory.find(serial).map(d -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("device", d);
            out.put("inSync", d.inSync());
            out.put("configHistory", configs.history(serial, 20));
            out.put("recentEvents", events(null, serial, null, 30));
            return ResponseEntity.ok(out);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** The config the controller intends the device to run. */
    @GetMapping(value = "/devices/{serial}/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> intendedConfig(@PathVariable String serial,
                                                 @RequestParam(required = false) Long version) {
        Device d = inventory.find(serial).orElse(null);
        if (d == null || d.intendedVersion() == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Configs.canonical(configs.configAt(serial, version == null ? d.intendedVersion() : version)));
    }

    @PostMapping("/devices/{serial}/decommission")
    public ResponseEntity<Void> decommission(@PathVariable String serial) {
        return inventory.decommission(serial) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // ---- templates -------------------------------------------------------------

    public record NewTemplate(String name, JsonNode body, String comment) {
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateService.Template createTemplate(@RequestBody NewTemplate t) {
        return templates.create(t.name(), t.body(), t.comment());
    }

    @GetMapping("/templates")
    public List<TemplateService.Template> templates() {
        return templates.list();
    }

    @GetMapping("/templates/{name}/{version}")
    public ResponseEntity<TemplateService.Template> template(@PathVariable String name, @PathVariable int version) {
        return ResponseEntity.of(templates.get(name, version));
    }

    @GetMapping("/templates/{name}/diff")
    public List<String> templateDiff(@PathVariable String name, @RequestParam int from, @RequestParam int to) {
        return templates.diff(name, from, to).stream().map(Object::toString).toList();
    }

    /** What a template version would render to on one device, and how that differs from its current config. */
    @GetMapping("/templates/{name}/{version}/preview")
    public Map<String, Object> preview(@PathVariable String name, @PathVariable int version,
                                       @RequestParam String serial) {
        Device d = inventory.find(serial).orElseThrow(() -> new IllegalArgumentException("no device " + serial));
        JsonNode rendered = configs.render(d, name, version);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", rendered);
        out.put("hash", Configs.hash(rendered));
        if (d.intendedVersion() > 0) {
            out.put("changesFromCurrent", ConfigDiff.diff(configs.configAt(serial, d.intendedVersion()), rendered)
                    .stream().map(Object::toString).toList());
        }
        return out;
    }

    public record Baseline(String template, int version) {
    }

    /** Sets the config newly onboarded devices in a group receive as day-0. */
    @PutMapping("/groups/{group}/baseline")
    @Transactional
    public Baseline setBaseline(@PathVariable String group, @RequestBody Baseline b) {
        templates.get(b.template(), b.version())
                .orElseThrow(() -> new IllegalArgumentException("no template " + b.template() + " v" + b.version()));
        jdbc.sql("""
                INSERT INTO group_baselines (device_group, template_name, template_version) VALUES (?, ?, ?)
                ON CONFLICT (device_group) DO UPDATE SET template_name = EXCLUDED.template_name,
                    template_version = EXCLUDED.template_version, updated_at = now()""")
                .params(group, b.template(), b.version()).update();
        return b;
    }

    // ---- deployments -----------------------------------------------------------

    @PostMapping("/deployments")
    @ResponseStatus(HttpStatus.CREATED)
    public DeploymentService.Deployment createDeployment(@RequestBody DeploymentService.CreateRequest r) {
        return deployments.create(r);
    }

    @GetMapping("/deployments")
    public List<DeploymentService.Deployment> deployments(@RequestParam(defaultValue = "50") int limit) {
        return deployments.list(Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/deployments/{id}")
    public ResponseEntity<DeploymentService.Status> deployment(@PathVariable UUID id) {
        return ResponseEntity.of(deployments.status(id));
    }

    @PostMapping("/deployments/{id}/pause")
    public DeploymentService.Deployment pause(@PathVariable UUID id) {
        return deployments.pause(id);
    }

    @PostMapping("/deployments/{id}/resume")
    public DeploymentService.Deployment resume(@PathVariable UUID id) {
        return deployments.resume(id);
    }

    @PostMapping("/deployments/{id}/rollback")
    public DeploymentService.Deployment rollback(@PathVariable UUID id) {
        return deployments.rollback(id);
    }

    // ---- fleet, events, pki ----------------------------------------------------

    @GetMapping("/fleet/summary")
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byState", jdbc.sql("SELECT state, count(*) FROM devices GROUP BY state ORDER BY state")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                .stream().collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll));
        out.put("onboarded", jdbc.sql("""
                SELECT count(*) FILTER (WHERE connected) AS connected,
                       count(*) FILTER (WHERE intended_version > 0 AND applied_version = intended_version
                                          AND intended_hash = running_hash) AS in_sync,
                       count(*) FILTER (WHERE applied_version < intended_version) AS behind,
                       count(*) FILTER (WHERE drifted_since IS NOT NULL) AS drifted,
                       count(*) FILTER (WHERE healthy = FALSE) AS unhealthy
                  FROM devices WHERE state = 'ONBOARDED'""")
                .query((rs, i) -> Map.of("connected", rs.getLong(1), "inSync", rs.getLong(2),
                        "behind", rs.getLong(3), "drifted", rs.getLong(4), "unhealthy", rs.getLong(5)))
                .single());
        out.put("sessionsOnThisInstance", sessions.size());
        out.put("activeDeployments", jdbc.sql(
                "SELECT count(*) FROM deployments WHERE state IN ('RUNNING','PAUSED','ROLLING_BACK')")
                .query(Long.class).single());
        return out;
    }

    public record Event(long id, String at, String serial, UUID deploymentId, String kind, JsonNode detail) {
    }

    /** The event log, oldest first after {@code afterId}; poll with the last id seen. */
    @GetMapping("/events")
    public List<Event> events(@RequestParam(required = false) Long afterId,
                              @RequestParam(required = false) String serial,
                              @RequestParam(required = false) UUID deploymentId,
                              @RequestParam(defaultValue = "200") int limit) {
        return jdbc.sql("""
                SELECT id, at, serial, deployment_id, kind, detail::text FROM events
                 WHERE id > :after
                   AND (CAST(:serial AS text) IS NULL OR serial = :serial)
                   AND (CAST(:dep AS uuid) IS NULL OR deployment_id = :dep)
                 ORDER BY id LIMIT :limit""")
                .param("after", afterId == null ? 0L : afterId)
                .param("serial", serial)
                .param("dep", deploymentId)
                .param("limit", Math.min(Math.max(limit, 1), 2000))
                .query((rs, i) -> new Event(rs.getLong(1), rs.getTimestamp(2).toInstant().toString(),
                        rs.getString(3), (UUID) rs.getObject(4), rs.getString(5), Configs.parse(rs.getString(6))))
                .list();
    }

    /** The CA certificate, for tooling. Devices get it out of band, as their trust anchor. */
    @GetMapping(value = "/pki/ca.crt", produces = "application/x-pem-file")
    public String caCertificate() {
        return CertificateAuthority.pem(ca.caCertificate());
    }
}
