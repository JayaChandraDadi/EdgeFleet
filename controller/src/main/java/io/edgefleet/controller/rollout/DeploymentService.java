package io.edgefleet.controller.rollout;

import io.edgefleet.controller.config.ConfigService;
import io.edgefleet.controller.config.TemplateService;
import io.edgefleet.controller.device.Device;
import io.edgefleet.controller.infra.Events;
import java.sql.Array;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creating and controlling staged rollouts. The engine that drives them is {@link RolloutEngine}. */
@Service
public class DeploymentService {

    /**
     * @param batchPercents   cumulative share of the group per batch, ending at 100, e.g. [5, 25, 100]
     * @param maxFailurePct   a batch whose failure rate exceeds this halts the rollout and rolls it back
     * @param healthTimeoutSec how long a device has to apply and pass its health check
     */
    public record CreateRequest(String template, int version, String group, List<Integer> batchPercents,
                                int maxFailurePct, int healthTimeoutSec) {
    }

    public record Deployment(UUID id, String template, int templateVersion, String group, List<Integer> batchPercents,
                             int maxFailurePct, int healthTimeoutSec, String state, int currentBatch,
                             Instant batchStartedAt, String reason, Instant createdAt, Instant finishedAt) {
        public int batches() {
            return batchPercents.size();
        }
    }

    /** Per-batch counts of target states. */
    public record BatchStatus(int batch, int targets, Map<String, Integer> byState) {
    }

    public record Status(Deployment deployment, List<BatchStatus> batches) {
    }

    public static final class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** Some devices cannot render or validate the template; nothing was created. */
    public static final class PreflightFailedException extends RuntimeException {
        private final Map<String, List<String>> failures;

        public PreflightFailedException(Map<String, List<String>> failures) {
            super(failures.size() + " devices fail pre-flight");
            this.failures = failures;
        }

        public Map<String, List<String>> failures() {
            return failures;
        }
    }

    static final String COLUMNS = """
            id, template_name, template_version, device_group, batch_percents, max_failure_pct, health_timeout_sec,
            state, current_batch, batch_started_at, reason, created_at, finished_at""";

    static final org.springframework.jdbc.core.RowMapper<Deployment> MAPPER = (rs, i) -> {
        Array arr = rs.getArray("batch_percents");
        List<Integer> pcts = new ArrayList<>();
        for (Object o : (Object[]) arr.getArray()) {
            pcts.add(((Number) o).intValue());
        }
        return new Deployment((UUID) rs.getObject("id"), rs.getString("template_name"),
                rs.getInt("template_version"), rs.getString("device_group"), pcts, rs.getInt("max_failure_pct"),
                rs.getInt("health_timeout_sec"), rs.getString("state"), rs.getInt("current_batch"),
                ts(rs.getTimestamp("batch_started_at")), rs.getString("reason"),
                ts(rs.getTimestamp("created_at")), ts(rs.getTimestamp("finished_at")));
    };

    private final JdbcClient jdbc;
    private final TemplateService templates;
    private final ConfigService configs;
    private final Events events;

    public DeploymentService(JdbcClient jdbc, TemplateService templates, ConfigService configs, Events events) {
        this.jdbc = jdbc;
        this.templates = templates;
        this.configs = configs;
        this.events = events;
    }

    @Transactional
    public Deployment create(CreateRequest r) {
        List<Integer> p = r.batchPercents() == null || r.batchPercents().isEmpty() ? List.of(100) : r.batchPercents();
        for (int i = 0; i < p.size(); i++) {
            if (p.get(i) < 1 || p.get(i) > 100 || (i > 0 && p.get(i) <= p.get(i - 1))) {
                throw new IllegalArgumentException("batchPercents must increase strictly within 1..100");
            }
        }
        if (p.getLast() != 100) {
            throw new IllegalArgumentException("the last batch must reach 100 percent");
        }
        if (r.maxFailurePct() < 0 || r.maxFailurePct() > 100) {
            throw new IllegalArgumentException("maxFailurePct must be 0..100");
        }
        if (r.healthTimeoutSec() < 5 || r.healthTimeoutSec() > 600) {
            throw new IllegalArgumentException("healthTimeoutSec must be 5..600");
        }
        templates.get(r.template(), r.version())
                .orElseThrow(() -> new IllegalArgumentException("no template " + r.template() + " v" + r.version()));

        // One active rollout per group: two would fight over the same devices.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("group:" + r.group()).query().singleRow();
        boolean busy = jdbc.sql("""
                SELECT count(*) FROM deployments
                 WHERE device_group = ? AND state IN ('RUNNING', 'PAUSED', 'ROLLING_BACK')""")
                .param(r.group()).query(Long.class).single() > 0;
        if (busy) {
            throw new ConflictException("group " + r.group() + " already has an active deployment");
        }

        List<Device> targets = jdbc.sql("SELECT " + Device.COLUMNS
                        + " FROM devices WHERE device_group = ? AND state = 'ONBOARDED' ORDER BY serial")
                .param(r.group()).query(Device.MAPPER).list();
        Map<String, List<String>> failures = configs.preflight(targets, r.template(), r.version());
        if (!failures.isEmpty()) {
            throw new PreflightFailedException(failures);
        }

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO deployments (id, template_name, template_version, device_group, batch_percents,
                                         max_failure_pct, health_timeout_sec, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING')""")
                .params(id, r.template(), r.version(), r.group(), p.toArray(Integer[]::new), r.maxFailurePct(),
                        r.healthTimeoutSec())
                .update();

        // Shuffle so the canary is a random sample, not the lowest serials.
        List<Device> order = new ArrayList<>(targets);
        Collections.shuffle(order, new Random(id.getMostSignificantBits()));
        int n = order.size();
        int start = 0;
        for (int b = 0; b < p.size(); b++) {
            int end = b == p.size() - 1 ? n : Math.max(Math.min(n, start + 1), (int) Math.ceil(p.get(b) * n / 100.0));
            for (int i = start; i < end; i++) {
                jdbc.sql("INSERT INTO deployment_targets (deployment_id, serial, batch) VALUES (?, ?, ?)")
                        .params(id, order.get(i).serial(), b).update();
            }
            start = end;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("template", r.template() + " v" + r.version());
        detail.put("group", r.group());
        detail.put("targets", n);
        detail.put("batches", p);
        events.record("deployment.created", null, id, detail);
        return get(id).orElseThrow();
    }

    public Optional<Deployment> get(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM deployments WHERE id = ?").param(id).query(MAPPER).optional();
    }

    public List<Deployment> list(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM deployments ORDER BY created_at DESC LIMIT ?")
                .param(limit).query(MAPPER).list();
    }

    public Optional<Status> status(UUID id) {
        return get(id).map(d -> {
            Map<Integer, BatchStatus> batches = new LinkedHashMap<>();
            for (int b = 0; b < d.batches(); b++) {
                batches.put(b, new BatchStatus(b, 0, new LinkedHashMap<>()));
            }
            jdbc.sql("""
                    SELECT batch, state, count(*) FROM deployment_targets WHERE deployment_id = ?
                     GROUP BY batch, state ORDER BY batch, state""")
                    .param(id)
                    .query((rs, i) -> {
                        BatchStatus s = batches.get(rs.getInt(1));
                        s.byState().put(rs.getString(2), rs.getInt(3));
                        return null;
                    }).list();
            List<BatchStatus> out = new ArrayList<>();
            batches.values().forEach(s -> out.add(new BatchStatus(s.batch(),
                    s.byState().values().stream().mapToInt(Integer::intValue).sum(), s.byState())));
            return new Status(d, out);
        });
    }

    @Transactional
    public Deployment pause(UUID id) {
        return transition(id, "RUNNING", "PAUSED", "deployment.paused", "paused by operator");
    }

    @Transactional
    public Deployment resume(UUID id) {
        return transition(id, "PAUSED", "RUNNING", "deployment.resumed", null);
    }

    /** Operator-requested rollback of everything this deployment changed. */
    @Transactional
    public Deployment rollback(UUID id) {
        int n = jdbc.sql("""
                UPDATE deployments SET state = 'ROLLING_BACK', reason = 'rollback requested by operator',
                       batch_started_at = now(), updated_at = now()
                 WHERE id = ? AND state IN ('RUNNING', 'PAUSED', 'COMPLETED')""").param(id).update();
        if (n == 0) {
            throw new ConflictException("deployment cannot be rolled back in its current state");
        }
        events.record("deployment.rollback_requested", null, id, Map.of());
        return get(id).orElseThrow();
    }

    private Deployment transition(UUID id, String from, String to, String event, String reason) {
        int n = jdbc.sql("UPDATE deployments SET state = ?, reason = COALESCE(?, reason), updated_at = now() "
                        + "WHERE id = ? AND state = ?")
                .params(to, reason, id, from).update();
        if (n == 0) {
            throw new ConflictException("deployment is not " + from.toLowerCase());
        }
        events.record(event, null, id, Map.of());
        return get(id).orElseThrow();
    }

    private static Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
