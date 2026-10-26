package io.edgefleet.controller.rollout;

import io.edgefleet.controller.config.ConfigService;
import io.edgefleet.controller.device.Device;
import io.edgefleet.controller.infra.Events;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Advances staged rollouts, one small step per tick.
 *
 * <p>A batch is pushed, then judged: every device must apply the config and pass its
 * health check within the health timeout. If the batch's failure rate exceeds the
 * deployment's limit (or provably will, before all results are in), the rollout
 * halts and everything it changed is rolled back. Otherwise the next batch starts;
 * after the last one the template becomes the group's baseline.
 *
 * <p>All state lives in Postgres and each tick runs in one transaction under an
 * advisory lock, so only one controller instance drives rollouts at a time and a
 * restarted controller continues exactly where the last one stopped.
 *
 * <p>Devices offline when their batch starts are DEFERRED: their intended config is
 * still updated, so they converge when they reconnect, but they neither count as
 * failures nor hold up the rollout.
 */
@Component
public class RolloutEngine {

    private static final Logger log = LoggerFactory.getLogger(RolloutEngine.class);
    private static final long LOCK_KEY = 0x45646765466c74L; // "EdgeFlt"
    private static final int RESULT_GRACE_SEC = 15;

    private final JdbcClient jdbc;
    private final ConfigService configs;
    private final Events events;
    private final TransactionTemplate tx;

    public RolloutEngine(JdbcClient jdbc, ConfigService configs, Events events, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.configs = configs;
        this.events = events;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${edgefleet.rollout-tick:500ms}")
    public void tick() {
        try {
            tx.executeWithoutResult(status -> {
                boolean leader = jdbc.sql("SELECT pg_try_advisory_xact_lock(?)").param(LOCK_KEY)
                        .query(Boolean.class).single();
                if (!leader) {
                    return;
                }
                List<DeploymentService.Deployment> active = jdbc.sql("SELECT " + DeploymentService.COLUMNS
                                + " FROM deployments WHERE state IN ('RUNNING', 'ROLLING_BACK') ORDER BY created_at")
                        .query(DeploymentService.MAPPER).list();
                for (DeploymentService.Deployment d : active) {
                    if (d.state().equals("RUNNING")) {
                        advance(d);
                    } else {
                        advanceRollback(d);
                    }
                }
            });
        } catch (RuntimeException e) {
            log.warn("rollout tick failed: {}", e.toString());
        }
    }

    private void advance(DeploymentService.Deployment d) {
        if (d.batchStartedAt() == null) {
            startBatch(d);
            return;
        }
        // Targets that never reported back within the health timeout count as failed.
        jdbc.sql("""
                UPDATE deployment_targets SET state = 'FAILED', finished_at = now(),
                       detail = 'no result within health timeout'
                 WHERE deployment_id = ? AND batch = ? AND state = 'PUSHED'
                   AND pushed_at < now() - make_interval(secs => ?)""")
                .params(d.id(), d.currentBatch(), d.healthTimeoutSec() + RESULT_GRACE_SEC).update();

        Map<String, Integer> c = counts(d.id(), d.currentBatch());
        int pushed = c.getOrDefault("PUSHED", 0);
        int ok = c.getOrDefault("SUCCEEDED", 0);
        int failed = c.getOrDefault("FAILED", 0);
        int judged = pushed + ok + failed;

        // Halt as soon as the limit is certain to be exceeded, even with results pending.
        if (judged > 0 && failed * 100 > d.maxFailurePct() * judged) {
            beginRollback(d, String.format("batch %d: %d of %d devices failed (limit %d%%)",
                    d.currentBatch() + 1, failed, judged, d.maxFailurePct()));
            return;
        }
        if (pushed > 0) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batch", d.currentBatch() + 1);
        detail.put("succeeded", ok);
        detail.put("failed", failed);
        detail.put("deferred", c.getOrDefault("DEFERRED", 0));
        events.record("deployment.batch_passed", null, d.id(), detail);

        if (d.currentBatch() + 1 >= d.batches()) {
            jdbc.sql("UPDATE deployments SET state = 'COMPLETED', finished_at = now(), updated_at = now() WHERE id = ?")
                    .param(d.id()).update();
            jdbc.sql("""
                    INSERT INTO group_baselines (device_group, template_name, template_version) VALUES (?, ?, ?)
                    ON CONFLICT (device_group) DO UPDATE SET template_name = EXCLUDED.template_name,
                        template_version = EXCLUDED.template_version, updated_at = now()""")
                    .params(d.group(), d.template(), d.templateVersion()).update();
            events.record("deployment.completed", null, d.id(),
                    Map.of("template", d.template() + " v" + d.templateVersion(), "group", d.group()));
        } else {
            jdbc.sql("""
                    UPDATE deployments SET current_batch = current_batch + 1, batch_started_at = NULL,
                           updated_at = now() WHERE id = ?""").param(d.id()).update();
        }
    }

    private void startBatch(DeploymentService.Deployment d) {
        List<Device> devices = jdbc.sql("SELECT " + Device.COLUMNS + """
                 FROM devices
                WHERE state = 'ONBOARDED' AND serial IN (
                      SELECT serial FROM deployment_targets
                       WHERE deployment_id = ? AND batch = ? AND state = 'PENDING')""")
                .params(d.id(), d.currentBatch()).query(Device.MAPPER).list();
        int pushed = 0;
        int deferred = 0;
        for (Device dev : devices) {
            ConfigService.Assigned a = configs.assignTemplate(dev, d.template(), d.templateVersion(), "deployment",
                    d.id());
            String state = dev.connected() ? "PUSHED" : "DEFERRED";
            jdbc.sql("""
                    UPDATE deployment_targets SET state = ?, from_version = ?, to_version = ?, command_id = ?,
                           pushed_at = now() WHERE deployment_id = ? AND serial = ?""")
                    .params(state, dev.intendedVersion(), a.version(), a.commandId(), d.id(), dev.serial())
                    .update();
            if (dev.connected()) {
                pushed++;
            } else {
                deferred++;
            }
        }
        jdbc.sql("UPDATE deployments SET batch_started_at = now(), updated_at = now() WHERE id = ?")
                .param(d.id()).update();
        events.record("deployment.batch_started", null, d.id(),
                Map.of("batch", d.currentBatch() + 1, "pushed", pushed, "deferred", deferred));
    }

    private void beginRollback(DeploymentService.Deployment d, String reason) {
        jdbc.sql("""
                UPDATE deployments SET state = 'ROLLING_BACK', reason = ?, batch_started_at = now(),
                       updated_at = now() WHERE id = ?""").params(reason, d.id()).update();
        events.record("deployment.halted", null, d.id(), Map.of("reason", reason));
        log.info("deployment {} halted: {}", d.id(), reason);
    }

    /**
     * Restores the previous config on every device the deployment touched. Each
     * rollback is a new config version carrying the old content, so it outranks any
     * forward push still in flight.
     */
    private void advanceRollback(DeploymentService.Deployment d) {
        record Target(String serial, long fromVersion) {
        }
        List<Target> todo = jdbc.sql("""
                SELECT serial, from_version FROM deployment_targets
                 WHERE deployment_id = ? AND rollback_command_id IS NULL
                   AND state IN ('PUSHED', 'SUCCEEDED', 'FAILED', 'DEFERRED')""")
                .param(d.id()).query((rs, i) -> new Target(rs.getString(1), rs.getLong(2))).list();
        for (Target t : todo) {
            if (t.fromVersion() == 0) {
                jdbc.sql("""
                        UPDATE deployment_targets SET state = 'ROLLED_BACK', detail = 'no earlier config to restore'
                         WHERE deployment_id = ? AND serial = ?""").params(d.id(), t.serial()).update();
                continue;
            }
            ConfigService.Assigned a = configs.assign(t.serial(), configs.configAt(t.serial(), t.fromVersion()),
                    "rollback", null, null, d.id());
            jdbc.sql("UPDATE deployment_targets SET rollback_command_id = ? WHERE deployment_id = ? AND serial = ?")
                    .params(a.commandId(), d.id(), t.serial()).update();
        }
        if (!todo.isEmpty()) {
            events.record("deployment.rollback_pushed", null, d.id(), Map.of("devices", todo.size()));
            return;
        }

        // Done when every connected device confirmed its rollback; offline ones converge on reconnect.
        long waiting = jdbc.sql("""
                SELECT count(*) FROM deployment_targets t JOIN devices d ON d.serial = t.serial
                 WHERE t.deployment_id = ? AND t.rollback_command_id IS NOT NULL AND t.state <> 'ROLLED_BACK'
                   AND d.connected""").param(d.id()).query(Long.class).single();
        Instant started = d.batchStartedAt() == null ? Instant.now() : d.batchStartedAt();
        boolean timedOut = Instant.now().isAfter(started.plusSeconds(2L * d.healthTimeoutSec() + RESULT_GRACE_SEC));
        if (waiting == 0 || timedOut) {
            jdbc.sql("UPDATE deployments SET state = 'ROLLED_BACK', finished_at = now(), updated_at = now() WHERE id = ?")
                    .param(d.id()).update();
            events.record("deployment.rolled_back", null, d.id(),
                    Map.of("unconfirmed", waiting, "reason", String.valueOf(d.reason())));
        }
    }

    private Map<String, Integer> counts(UUID id, int batch) {
        Map<String, Integer> m = new LinkedHashMap<>();
        jdbc.sql("SELECT state, count(*) FROM deployment_targets WHERE deployment_id = ? AND batch = ? GROUP BY state")
                .params(id, batch)
                .query((rs, i) -> m.put(rs.getString(1), rs.getInt(2))).list();
        return m;
    }
}
