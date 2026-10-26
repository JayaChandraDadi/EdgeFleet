package io.edgefleet.controller.config;

import io.edgefleet.config.ConfigDiff;
import io.edgefleet.config.Configs;
import io.edgefleet.config.TemplateRenderer;
import io.edgefleet.controller.infra.Events;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Versioned config templates. A version, once created, never changes. */
@Service
public class TemplateService {

    public record Template(String name, int version, JsonNode body, String comment, Instant createdAt) {
        /** Variables the template needs from each device; returned by the API alongside the body. */
        @com.fasterxml.jackson.annotation.JsonProperty("variables")
        public List<String> variables() {
            return List.copyOf(TemplateRenderer.referencedVariables(body));
        }
    }

    private final JdbcClient jdbc;
    private final Events events;

    public TemplateService(JdbcClient jdbc, Events events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public Template create(String name, JsonNode body, String comment) {
        if (name == null || !name.matches("^[a-z0-9][a-z0-9-]{0,62}$")) {
            throw new IllegalArgumentException("template name must be lower-case letters, digits and '-'");
        }
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("template body must be a JSON object");
        }
        // Serialize version allocation per template name.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("template:" + name).query().singleRow();
        int version = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM templates WHERE name = ?")
                .param(name).query(Integer.class).single();
        jdbc.sql("INSERT INTO templates (name, version, body, comment) VALUES (?, ?, ?::jsonb, ?)")
                .params(name, version, Configs.canonical(body), comment).update();
        events.record("template.created", null, Map.of("name", name, "version", version));
        return get(name, version).orElseThrow();
    }

    public Optional<Template> get(String name, int version) {
        return jdbc.sql("SELECT name, version, body::text AS body, comment, created_at FROM templates "
                        + "WHERE name = ? AND version = ?")
                .params(name, version)
                .query((rs, i) -> new Template(rs.getString("name"), rs.getInt("version"),
                        Configs.parse(rs.getString("body")), rs.getString("comment"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    public List<Template> list() {
        return jdbc.sql("SELECT name, version, body::text AS body, comment, created_at FROM templates "
                        + "ORDER BY name, version DESC")
                .query((rs, i) -> new Template(rs.getString("name"), rs.getInt("version"),
                        Configs.parse(rs.getString("body")), rs.getString("comment"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public List<ConfigDiff.Change> diff(String name, int from, int to) {
        Template a = get(name, from).orElseThrow(() -> new IllegalArgumentException("no " + name + " v" + from));
        Template b = get(name, to).orElseThrow(() -> new IllegalArgumentException("no " + name + " v" + to));
        return ConfigDiff.diff(a.body(), b.body());
    }
}
