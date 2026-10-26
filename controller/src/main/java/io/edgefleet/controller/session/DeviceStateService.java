package io.edgefleet.controller.session;

import io.edgefleet.control.v1.ConfigResult;
import io.edgefleet.controller.EdgeFleetProperties;
import io.edgefleet.controller.config.ConfigService;
import io.edgefleet.controller.infra.Events;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the controller learns from devices, and the reconciliation that follows.
 *
 * <p>Reconciliation is level-triggered: on every hello and heartbeat the device's
 * reported state is compared with what the controller intends, and any gap produces
 * the same action no matter how it arose (missed push, controller restart, device
 * offline during a rollout, hand-edited config). Edge-triggered pushes alone would
 * leave a device stuck after any lost message.
 */
@Service
public class DeviceStateService {

    private final JdbcClient jdbc;
    private final ConfigService configs;
    private final Events events;
    private final EdgeFleetProperties props;
    private final ApplicationEventPublisher publisher;

    public DeviceStateService(JdbcClient jdbc, ConfigService configs, Events events, EdgeFleetProperties props,
                              ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.configs = configs;
        this.events = events;
        this.props = props;
        this.publisher = publisher;
    }

    /** Session opened. Pushes that were sent on an earlier session may never have arrived. */
    @Transactional
    public void onHello(String serial, long appliedVersion, String runningHash) {
        jdbc.sql("""
                UPDATE devices SET connected = TRUE, last_seen_at = now(), applied_version = ?, running_hash = ?
                 WHERE serial = ?""").params(appliedVersion, emptyToNull(runningHash), serial).update();
        jdbc.sql("UPDATE commands SET state = 'QUEUED', sent_at = NULL WHERE serial = ? AND state = 'SENT'")
                .param(serial).update();
        events.record("session.connected", serial, Map.of("appliedVersion", appliedVersion));
        reconcile(serial);
    }

    @Transactional
    public void onHeartbeat(String serial, long appliedVersion, String runningHash, boolean healthy) {
        jdbc.sql("""
                UPDATE devices SET connected = TRUE, last_seen_at = now(), applied_version = ?, running_hash = ?,
                       healthy = ? WHERE serial = ?""")
                .params(appliedVersion, emptyToNull(runningHash), healthy, serial).update();
        reconcile(serial);
    }

    @Transactional
    public void onDisconnect(String serial) {
        jdbc.sql("UPDATE devices SET connected = FALSE WHERE serial = ?").param(serial).update();
        events.record("session.disconnected", serial, Map.of());
    }

    /**
     * Records a push outcome. The device is the authority on what it runs, so its
     * reported running version and hash are taken as they are.
     */
    @Transactional
    public void onResult(String serial, ConfigResult r) {
        UUID commandId;
        try {
            commandId = UUID.fromString(r.getCommandId());
        } catch (IllegalArgumentException e) {
            return;
        }
        UUID deploymentId = jdbc.sql("""
                UPDATE commands SET state = 'DONE', result = ?, detail = ?, finished_at = now()
                 WHERE command_id = ? AND serial = ? AND state IN ('SENT', 'QUEUED')
                RETURNING deployment_id""")
                .params(r.getStatus().name(), r.getDetail(), commandId, serial)
                .query((rs, i) -> (UUID) rs.getObject(1)).optional().orElse(null);

        jdbc.sql("UPDATE devices SET applied_version = ?, running_hash = ?, last_seen_at = now() WHERE serial = ?")
                .params(r.getRunningVersion(), emptyToNull(r.getRunningHash()), serial).update();

        boolean ok = r.getStatus() == ConfigResult.Status.APPLIED_HEALTHY;
        // Rollout bookkeeping: a forward push or a rollback push of a deployment target.
        jdbc.sql("""
                UPDATE deployment_targets SET state = ?, detail = ?, finished_at = now()
                 WHERE serial = ? AND command_id = ? AND state = 'PUSHED'""")
                .params(ok ? "SUCCEEDED" : "FAILED", ok ? null : r.getStatus().name() + ": " + r.getDetail(),
                        serial, commandId)
                .update();
        jdbc.sql("""
                UPDATE deployment_targets SET state = 'ROLLED_BACK', finished_at = now()
                 WHERE serial = ? AND rollback_command_id = ?""")
                .params(serial, commandId).update();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("version", r.getVersion());
        detail.put("status", r.getStatus().name());
        detail.put("runningVersion", r.getRunningVersion());
        if (!r.getDetail().isEmpty()) {
            detail.put("detail", r.getDetail());
        }
        events.record(ok ? "config.applied" : "config.failed", serial, deploymentId, detail);
        reconcile(serial);
    }

    /** Compares intended and reported state and acts on the difference. */
    void reconcile(String serial) {
        record State(String state, long intended, String intendedHash, long applied, String runningHash,
                     java.sql.Timestamp driftedSince) {
        }
        State s = jdbc.sql("""
                SELECT state, intended_version, intended_hash, applied_version, running_hash, drifted_since
                  FROM devices WHERE serial = ? FOR UPDATE""")
                .param(serial)
                .query((rs, i) -> new State(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getTimestamp(6)))
                .optional().orElse(null);
        if (s == null || !"ONBOARDED".equals(s.state()) || s.intended() == 0) {
            return;
        }

        if (s.applied() < s.intended()) {
            // Behind. Make sure a push for the intended version exists, unless one was
            // already delivered and failed: retrying a config the device rejected or
            // reverted would loop forever; the rollout engine or an operator decides.
            record Cmd(UUID id, String state, String result) {
            }
            Cmd latest = jdbc.sql("""
                    SELECT command_id, state, result FROM commands WHERE serial = ? AND version = ?
                     ORDER BY created_at DESC LIMIT 1""")
                    .params(serial, s.intended())
                    .query((rs, i) -> new Cmd((UUID) rs.getObject(1), rs.getString(2), rs.getString(3)))
                    .optional().orElse(null);
            if (latest == null || "SUPERSEDED".equals(latest.state())) {
                UUID id = UUID.randomUUID();
                jdbc.sql("INSERT INTO commands (command_id, serial, version) VALUES (?, ?, ?)")
                        .params(id, serial, s.intended()).update();
                publisher.publishEvent(new ConfigService.CommandQueued(serial, id));
            } else if ("QUEUED".equals(latest.state())) {
                publisher.publishEvent(new ConfigService.CommandQueued(serial, latest.id()));
            }
            return;
        }

        boolean drifted = s.applied() == s.intended() && s.intendedHash() != null
                && !s.intendedHash().equals(s.runningHash());
        if (drifted) {
            if (s.driftedSince() == null) {
                jdbc.sql("UPDATE devices SET drifted_since = now() WHERE serial = ?").param(serial).update();
                events.record("config.drift_detected", serial,
                        Map.of("version", s.applied(), "expectedHash", s.intendedHash(),
                                "runningHash", String.valueOf(s.runningHash())));
            }
            boolean pending = jdbc.sql("SELECT count(*) FROM commands WHERE serial = ? AND state IN ('QUEUED','SENT')")
                    .param(serial).query(Long.class).single() > 0;
            if (props.driftAutoRemediate() && !pending) {
                configs.assign(serial, configs.configAt(serial, s.intended()), "drift-remediation", null, null, null);
            }
        } else if (s.driftedSince() != null && s.applied() >= s.intended()) {
            jdbc.sql("UPDATE devices SET drifted_since = NULL WHERE serial = ?").param(serial).update();
            events.record("config.drift_cleared", serial, Map.of("version", s.applied()));
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
