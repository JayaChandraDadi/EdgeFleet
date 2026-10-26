package io.edgefleet.controller.infra;

import io.edgefleet.config.Configs;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Appends to the event log. Call inside the transaction that made the change. */
@Component
public class Events {

    private final JdbcClient jdbc;

    public Events(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String kind, String serial, UUID deploymentId, Map<String, ?> detail) {
        jdbc.sql("INSERT INTO events (kind, serial, deployment_id, detail) VALUES (?, ?, ?, ?::jsonb)")
                .params(kind, serial, deploymentId, Configs.JSON.writeValueAsString(detail))
                .update();
    }

    public void record(String kind, String serial, Map<String, ?> detail) {
        record(kind, serial, null, detail);
    }
}
