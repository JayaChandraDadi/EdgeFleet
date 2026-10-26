package io.edgefleet.controller.session;

import io.edgefleet.control.v1.ConfigPush;
import io.edgefleet.controller.config.ConfigService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued pushes over the device's session.
 *
 * <p>The commands table is the source of truth; the session is just transport. A
 * push is sent right after the transaction that queued it commits, and a sweep
 * picks up anything that missed that moment (device was offline, controller
 * restarted). Only the newest queued push for a device is sent; older ones were
 * superseded when it was queued. Rows are claimed with {@code FOR UPDATE SKIP
 * LOCKED} so two controller instances never send the same push.
 */
@Component
public class CommandDispatcher {

    private static final int DEFAULT_HEALTH_TIMEOUT_SEC = 30;

    private final JdbcClient jdbc;
    private final SessionRegistry registry;
    private final TransactionTemplate tx;
    private final Counter sent;

    public CommandDispatcher(JdbcClient jdbc, SessionRegistry registry, TransactionTemplate tx,
                             MeterRegistry meters) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.tx = tx;
        this.sent = Counter.builder("edgefleet.pushes.sent").register(meters);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQueued(ConfigService.CommandQueued e) {
        dispatch(e.serial());
    }

    @Scheduled(fixedDelayString = "${edgefleet.dispatch-interval:250ms}")
    public void sweep() {
        Set<String> local = registry.connectedSerials();
        if (local.isEmpty()) {
            return;
        }
        List<String> waiting = jdbc.sql("""
                SELECT DISTINCT serial FROM commands WHERE state = 'QUEUED' AND serial = ANY(?) LIMIT 2000""")
                .param(local.toArray(String[]::new))
                .query(String.class).list();
        waiting.forEach(this::dispatch);
    }

    /** Sends the device's newest queued push if its session lives on this instance. */
    public void dispatch(String serial) {
        if (registry.get(serial).isEmpty()) {
            return;
        }
        tx.executeWithoutResult(status -> sendOne(serial));
    }

    @Transactional
    void sendOne(String serial) {
        record Pending(UUID id, long version, String config, String hash, Integer timeout) {
        }
        Pending p = jdbc.sql("""
                SELECT c.command_id, c.version, dc.config::text, dc.config_hash, d.health_timeout_sec
                  FROM commands c
                  JOIN device_configs dc ON dc.serial = c.serial AND dc.version = c.version
                  LEFT JOIN deployments d ON d.id = c.deployment_id
                 WHERE c.serial = ? AND c.state = 'QUEUED'
                 ORDER BY c.version DESC, c.created_at DESC
                 LIMIT 1
                   FOR UPDATE OF c SKIP LOCKED""")
                .param(serial)
                .query((rs, i) -> new Pending((UUID) rs.getObject(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), (Integer) rs.getObject(5)))
                .optional().orElse(null);
        if (p == null) {
            return;
        }
        boolean delivered = registry.get(serial)
                .map(s -> s.send(ConfigPush.newBuilder()
                        .setCommandId(p.id().toString())
                        .setVersion(p.version())
                        .setConfigJson(p.config())
                        .setConfigHash(p.hash())
                        .setHealthTimeoutSec(p.timeout() == null ? DEFAULT_HEALTH_TIMEOUT_SEC : p.timeout())
                        .build()))
                .orElse(false);
        if (delivered) {
            jdbc.sql("UPDATE commands SET state = 'SENT', sent_at = now() WHERE command_id = ?")
                    .param(p.id()).update();
            jdbc.sql("""
                    UPDATE commands SET state = 'SUPERSEDED', finished_at = now()
                     WHERE serial = ? AND state = 'QUEUED' AND command_id <> ?""")
                    .params(serial, p.id()).update();
            sent.increment();
        }
    }
}
