package io.edgefleet.controller.device;

import java.time.Instant;

/** A device as stored by the controller. */
public record Device(
        String serial,
        String model,
        String group,
        String hostname,
        String systemIp,
        long siteId,
        String region,
        String variablesJson,
        String state,
        String certSerial,
        Instant onboardedAt,
        long intendedVersion,
        String intendedHash,
        long appliedVersion,
        String runningHash,
        Boolean healthy,
        Instant driftedSince,
        boolean connected,
        Instant lastSeenAt) {

    /** Column list matching {@link #MAPPER}. */
    public static final String COLUMNS = """
            serial, model, device_group, hostname, host(system_ip) AS system_ip, site_id, region,
            variables::text AS variables, state, cert_serial, onboarded_at, intended_version, intended_hash,
            applied_version, running_hash, healthy, drifted_since, connected, last_seen_at""";

    public static final org.springframework.jdbc.core.RowMapper<Device> MAPPER = (rs, i) -> new Device(
            rs.getString("serial"),
            rs.getString("model"),
            rs.getString("device_group"),
            rs.getString("hostname"),
            rs.getString("system_ip"),
            rs.getLong("site_id"),
            rs.getString("region"),
            rs.getString("variables"),
            rs.getString("state"),
            rs.getString("cert_serial"),
            ts(rs.getTimestamp("onboarded_at")),
            rs.getLong("intended_version"),
            rs.getString("intended_hash"),
            rs.getLong("applied_version"),
            rs.getString("running_hash"),
            (Boolean) rs.getObject("healthy"),
            ts(rs.getTimestamp("drifted_since")),
            rs.getBoolean("connected"),
            ts(rs.getTimestamp("last_seen_at")));

    private static Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** True when the device runs exactly the config the controller intends for it. */
    public boolean inSync() {
        return intendedVersion > 0 && appliedVersion == intendedVersion && intendedHash != null
                && intendedHash.equals(runningHash);
    }
}
