package io.edgefleet.controller.device;

import io.edgefleet.config.Configs;
import io.edgefleet.controller.EdgeFleetProperties;
import io.edgefleet.controller.infra.Events;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The provisioning file: which serial numbers may join, what identity each gets,
 * and the per-device variables its templates are rendered with. Importing a device
 * issues a one-time bootstrap token; only its SHA-256 is stored.
 */
@Service
public class InventoryService {

    /** One line of the provisioning file. */
    public record DeviceSpec(String serial, String model, String group, String hostname, String systemIp,
                             long siteId, String region, Map<String, Object> variables) {
    }

    /** Returned once at import; the plaintext token is not stored anywhere. */
    public record Imported(String serial, String bootstrapToken, Instant expiresAt) {
    }

    /** Published when a device is decommissioned, so its live session can be closed. */
    public record Decommissioned(String serial) {
    }

    /** Rejected input, with one message per problem. */
    public static final class InvalidInventoryException extends RuntimeException {
        private final List<String> problems;

        public InvalidInventoryException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    private static final Pattern SERIAL = Pattern.compile("^[A-Z0-9][A-Z0-9-]{3,39}$");
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern HOSTNAME = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final EdgeFleetProperties props;
    private final Events events;
    private final org.springframework.context.ApplicationEventPublisher publisher;

    public InventoryService(JdbcClient jdbc, EdgeFleetProperties props, Events events,
                            org.springframework.context.ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.props = props;
        this.events = events;
        this.publisher = publisher;
    }

    @Transactional
    public List<Imported> importDevices(List<DeviceSpec> specs) {
        List<String> problems = new ArrayList<>();
        for (DeviceSpec s : specs) {
            String at = "device " + s.serial() + ": ";
            if (s.serial() == null || !SERIAL.matcher(s.serial()).matches()) {
                problems.add(at + "serial must be 4-40 upper-case letters, digits or '-'");
            }
            if (s.systemIp() == null || !IPV4.matcher(s.systemIp()).matches()) {
                problems.add(at + "systemIp must be an IPv4 address");
            }
            if (s.hostname() == null || !HOSTNAME.matcher(s.hostname()).matches()) {
                problems.add(at + "hostname is not a valid hostname");
            }
            if (s.group() == null || s.group().isBlank() || s.region() == null || s.region().isBlank()
                    || s.model() == null || s.model().isBlank()) {
                problems.add(at + "model, group and region are required");
            }
            if (s.siteId() < 1) {
                problems.add(at + "siteId must be positive");
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidInventoryException(problems);
        }

        Instant expires = Instant.now().plus(props.tokenTtl());
        List<Imported> out = new ArrayList<>(specs.size());
        for (DeviceSpec s : specs) {
            String state = jdbc.sql("SELECT state FROM devices WHERE serial = ? FOR UPDATE")
                    .param(s.serial()).query(String.class).optional().orElse(null);
            if (state != null && !state.equals("ALLOWED")) {
                problems.add("device " + s.serial() + ": already " + state.toLowerCase()
                        + "; decommission and re-import to re-provision");
                continue;
            }
            String token = newToken();
            String vars = Configs.JSON.writeValueAsString(s.variables() == null ? Map.of() : s.variables());
            try {
                jdbc.sql("""
                        INSERT INTO devices (serial, model, device_group, hostname, system_ip, site_id, region,
                                             variables, token_hash, token_expires_at)
                        VALUES (?, ?, ?, ?, ?::inet, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (serial) DO UPDATE SET model = EXCLUDED.model,
                            device_group = EXCLUDED.device_group, hostname = EXCLUDED.hostname,
                            system_ip = EXCLUDED.system_ip, site_id = EXCLUDED.site_id, region = EXCLUDED.region,
                            variables = EXCLUDED.variables, token_hash = EXCLUDED.token_hash,
                            token_expires_at = EXCLUDED.token_expires_at""")
                        .params(s.serial(), s.model(), s.group(), s.hostname(), s.systemIp(), s.siteId(),
                                s.region(), vars, sha256(token), java.sql.Timestamp.from(expires))
                        .update();
            } catch (org.springframework.dao.DuplicateKeyException e) {
                problems.add("device " + s.serial() + ": systemIp " + s.systemIp() + " is already assigned");
                continue;
            }
            events.record("device.allowed", s.serial(), Map.of("group", s.group(), "systemIp", s.systemIp()));
            out.add(new Imported(s.serial(), token, expires));
        }
        if (!problems.isEmpty()) {
            throw new InvalidInventoryException(problems); // rolls back the whole import
        }
        return out;
    }

    public Optional<Device> find(String serial) {
        return jdbc.sql("SELECT " + Device.COLUMNS + " FROM devices WHERE serial = ?")
                .param(serial).query(Device.MAPPER).optional();
    }

    public List<Device> list(String group, String state, int limit, int offset) {
        return jdbc.sql("SELECT " + Device.COLUMNS + """
                         FROM devices
                        WHERE (CAST(:group AS text) IS NULL OR device_group = :group)
                          AND (CAST(:state AS text) IS NULL OR state = :state)
                        ORDER BY site_id, serial LIMIT :limit OFFSET :offset""")
                .param("group", group).param("state", state).param("limit", limit).param("offset", offset)
                .query(Device.MAPPER).list();
    }

    /** Revokes the device's identity: its session is refused from now on. */
    @Transactional
    public boolean decommission(String serial) {
        int n = jdbc.sql("""
                UPDATE devices SET state = 'DECOMMISSIONED', token_hash = NULL, connected = FALSE
                 WHERE serial = ? AND state <> 'DECOMMISSIONED'""").param(serial).update();
        if (n > 0) {
            jdbc.sql("UPDATE commands SET state = 'SUPERSEDED' WHERE serial = ? AND state IN ('QUEUED', 'SENT')")
                    .param(serial).update();
            events.record("device.decommissioned", serial, Map.of());
            publisher.publishEvent(new Decommissioned(serial));
        }
        return n > 0;
    }

    static String newToken() {
        byte[] b = new byte[20];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
