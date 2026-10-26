package io.edgefleet.controller.device;

import io.edgefleet.controller.EdgeFleetProperties;
import io.edgefleet.controller.config.ConfigService;
import io.edgefleet.controller.infra.Events;
import io.edgefleet.controller.pki.CertificateAuthority;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Zero-touch claim: a device that is in the provisioning file presents its serial,
 * its one-time bootstrap token and a CSR, and gets back its identity certificate,
 * its system IP and where to open its control session. The token is spent on use.
 * If the device's group has a baseline template, the day-0 config is queued in the
 * same transaction, so the device gets it as soon as its session opens.
 */
@Service
public class OnboardingService {

    /** Claim refused. The message never says which check failed, to give nothing away. */
    public static final class ClaimDeniedException extends RuntimeException {
        public ClaimDeniedException() {
            super("claim denied: unknown device, wrong or expired token, or already claimed");
        }
    }

    public record Claimed(X509Certificate certificate, String systemIp, long siteId, String hostname) {
    }

    private final JdbcClient jdbc;
    private final CertificateAuthority ca;
    private final EdgeFleetProperties props;
    private final ConfigService configs;
    private final Events events;
    private final InventoryService inventory;

    public OnboardingService(JdbcClient jdbc, CertificateAuthority ca, EdgeFleetProperties props,
                             ConfigService configs, Events events, InventoryService inventory) {
        this.jdbc = jdbc;
        this.ca = ca;
        this.props = props;
        this.configs = configs;
        this.events = events;
        this.inventory = inventory;
    }

    @Transactional(noRollbackFor = ClaimDeniedException.class)
    public Claimed claim(String serial, String model, String token, byte[] csrDer) {
        record Row(String model, String state, String tokenHash, java.sql.Timestamp expires) {
        }
        Row row = jdbc.sql("SELECT model, state, token_hash, token_expires_at FROM devices WHERE serial = ? FOR UPDATE")
                .param(serial)
                .query((rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4)))
                .optional().orElse(null);
        boolean ok = row != null
                && "ALLOWED".equals(row.state())
                && row.tokenHash() != null
                && row.expires() != null && row.expires().toInstant().isAfter(Instant.now())
                && row.model().equals(model)
                && MessageDigest.isEqual(row.tokenHash().getBytes(StandardCharsets.US_ASCII),
                        InventoryService.sha256(token == null ? "" : token).getBytes(StandardCharsets.US_ASCII));
        if (!ok) {
            if (row != null) {
                events.record("device.claim_denied", serial, Map.of("state", row.state()));
            }
            throw new ClaimDeniedException();
        }

        X509Certificate cert = ca.signDeviceCsr(csrDer, serial, props.deviceCertValidity());
        jdbc.sql("""
                UPDATE devices SET state = 'ONBOARDED', token_hash = NULL, token_expires_at = NULL,
                       cert_serial = ?, cert_not_after = ?, onboarded_at = now()
                 WHERE serial = ?""")
                .params(cert.getSerialNumber().toString(16), java.sql.Timestamp.from(cert.getNotAfter().toInstant()),
                        serial)
                .update();
        events.record("device.onboarded", serial, Map.of("certSerial", cert.getSerialNumber().toString(16)));

        Device device = inventory.find(serial).orElseThrow();
        jdbc.sql("SELECT template_name, template_version FROM group_baselines WHERE device_group = ?")
                .param(device.group())
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getInt(2)))
                .optional()
                .ifPresent(b -> configs.assignTemplate(device, b.getKey(), b.getValue(), "day0", null));
        return new Claimed(cert, device.systemIp(), device.siteId(), device.hostname());
    }
}
