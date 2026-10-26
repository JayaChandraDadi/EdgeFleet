package io.edgefleet.controller.config;

import io.edgefleet.config.ConfigValidator;
import io.edgefleet.config.Configs;
import io.edgefleet.config.TemplateRenderer;
import io.edgefleet.controller.device.Device;
import io.edgefleet.controller.infra.Events;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.StringNode;

/**
 * Renders configs for devices and records what each device should run.
 *
 * <p>Every change to a device's intended config gets a new, strictly increasing
 * version number, and a push command for it. Versions are never reused, not even
 * for rollbacks (a rollback is a new version whose content is an older config), so
 * a device can always discard a push that is older than what it runs. That makes a
 * late or duplicated delivery harmless.
 */
@Service
public class ConfigService {

    /** Rendering or validation failed for one device. */
    public static final class ConfigException extends RuntimeException {
        private final List<String> problems;

        public ConfigException(String serial, List<String> problems) {
            super(serial + ": " + String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    /** A new intended config was recorded and a push queued for it. */
    public record Assigned(String serial, long version, String hash, UUID commandId) {
    }

    /** Published after a push is queued, so the dispatcher can send it right away. */
    public record CommandQueued(String serial, UUID commandId) {
    }

    private final JdbcClient jdbc;
    private final TemplateService templates;
    private final Events events;
    private final ApplicationEventPublisher publisher;

    public ConfigService(JdbcClient jdbc, TemplateService templates, Events events,
                         ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.templates = templates;
        this.events = events;
        this.publisher = publisher;
    }

    /** Variables a template sees: the device's own plus its identity fields. */
    public static Map<String, JsonNode> variablesOf(Device d) {
        Map<String, JsonNode> vars = new HashMap<>();
        JsonNode own = Configs.parse(d.variablesJson());
        for (Map.Entry<String, JsonNode> e : own.properties()) {
            vars.put(e.getKey(), e.getValue());
        }
        vars.put("serial", StringNode.valueOf(d.serial()));
        vars.put("hostname", StringNode.valueOf(d.hostname()));
        vars.put("system_ip", StringNode.valueOf(d.systemIp()));
        vars.put("site_id", d.siteId() <= Integer.MAX_VALUE ? IntNode.valueOf((int) d.siteId())
                : LongNode.valueOf(d.siteId()));
        vars.put("region", StringNode.valueOf(d.region()));
        return vars;
    }

    /** Renders and validates a template for one device without changing anything. */
    public JsonNode render(Device d, String templateName, int templateVersion) {
        TemplateService.Template t = templates.get(templateName, templateVersion)
                .orElseThrow(() -> new IllegalArgumentException("no template " + templateName + " v" + templateVersion));
        JsonNode config;
        try {
            config = TemplateRenderer.render(t.body(), variablesOf(d));
        } catch (TemplateRenderer.MissingVariablesException e) {
            throw new ConfigException(d.serial(), List.of("missing variables: " + String.join(", ", e.missing())));
        }
        List<ConfigValidator.Violation> violations = ConfigValidator.validate(config);
        if (!violations.isEmpty()) {
            throw new ConfigException(d.serial(), violations.stream().map(Object::toString).toList());
        }
        return config;
    }

    /** Renders for many devices and returns every device that fails, with its problems. */
    public Map<String, List<String>> preflight(List<Device> devices, String templateName, int templateVersion) {
        Map<String, List<String>> failures = new LinkedHashMap<>();
        for (Device d : devices) {
            try {
                render(d, templateName, templateVersion);
            } catch (ConfigException e) {
                failures.put(d.serial(), e.problems());
            }
        }
        return failures;
    }

    /**
     * Records {@code config} as the device's new intended config under the next version
     * number and queues a push for it, superseding any push not yet sent.
     */
    @Transactional
    public Assigned assign(String serial, JsonNode config, String reason, String templateName,
                           Integer templateVersion, UUID deploymentId) {
        long next = jdbc.sql("""
                SELECT GREATEST(intended_version, applied_version) + 1 FROM devices
                 WHERE serial = ? AND state <> 'DECOMMISSIONED' FOR UPDATE""")
                .param(serial).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("no active device " + serial));
        String hash = Configs.hash(config);
        jdbc.sql("""
                INSERT INTO device_configs (serial, version, template_name, template_version, config, config_hash,
                                            reason, deployment_id)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)""")
                .params(serial, next, templateName, templateVersion, Configs.canonical(config), hash, reason,
                        deploymentId)
                .update();
        jdbc.sql("UPDATE devices SET intended_version = ?, intended_hash = ? WHERE serial = ?")
                .params(next, hash, serial).update();
        jdbc.sql("UPDATE commands SET state = 'SUPERSEDED', finished_at = now() WHERE serial = ? AND state = 'QUEUED'")
                .param(serial).update();
        UUID commandId = UUID.randomUUID();
        jdbc.sql("INSERT INTO commands (command_id, serial, version, deployment_id) VALUES (?, ?, ?, ?)")
                .params(commandId, serial, next, deploymentId).update();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("version", next);
        detail.put("reason", reason);
        if (templateName != null) {
            detail.put("template", templateName + " v" + templateVersion);
        }
        events.record("config.assigned", serial, deploymentId, detail);
        publisher.publishEvent(new CommandQueued(serial, commandId));
        return new Assigned(serial, next, hash, commandId);
    }

    /** Assigns a template-rendered config. */
    @Transactional
    public Assigned assignTemplate(Device d, String templateName, int templateVersion, String reason,
                                   UUID deploymentId) {
        return assign(d.serial(), render(d, templateName, templateVersion), reason, templateName, templateVersion,
                deploymentId);
    }

    /** The stored config for one device version. */
    public JsonNode configAt(String serial, long version) {
        return jdbc.sql("SELECT config::text FROM device_configs WHERE serial = ? AND version = ?")
                .params(serial, version).query(String.class).optional()
                .map(Configs::parse)
                .orElseThrow(() -> new IllegalArgumentException("no config v" + version + " for " + serial));
    }

    public record ConfigVersion(long version, String hash, String reason, String template, UUID deploymentId,
                                java.time.Instant createdAt) {
    }

    public List<ConfigVersion> history(String serial, int limit) {
        return new ArrayList<>(jdbc.sql("""
                SELECT version, config_hash, reason, template_name, template_version, deployment_id, created_at
                  FROM device_configs WHERE serial = ? ORDER BY version DESC LIMIT ?""")
                .params(serial, limit)
                .query((rs, i) -> new ConfigVersion(rs.getLong("version"), rs.getString("config_hash"),
                        rs.getString("reason"),
                        rs.getString("template_name") == null ? null
                                : rs.getString("template_name") + " v" + rs.getInt("template_version"),
                        (UUID) rs.getObject("deployment_id"), rs.getTimestamp("created_at").toInstant()))
                .list());
    }
}
