package io.edgefleet.controller;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param pkiDir              where the CA and the controller's TLS keys live
 * @param tlsHostnames        names the controller's TLS certificate is valid for
 * @param bootstrapPort       server-authenticated TLS port for device claims
 * @param sessionPort         mutual-TLS port for device control sessions
 * @param sessionTarget       host:port devices are told to use for their session
 * @param tokenTtl            how long a bootstrap token stays valid after import
 * @param deviceCertValidity  lifetime of issued device certificates
 * @param offlineAfter        a device with no message for this long is considered offline
 * @param driftAutoRemediate  push the intended config again when a device drifts
 * @param dispatchInterval    how often queued commands are swept to connected devices
 * @param rolloutTick         how often the rollout engine advances deployments
 */
@ConfigurationProperties(prefix = "edgefleet")
public record EdgeFleetProperties(
        @DefaultValue("./.edgefleet/pki") Path pkiDir,
        @DefaultValue({"localhost", "127.0.0.1"}) List<String> tlsHostnames,
        @DefaultValue("50051") int bootstrapPort,
        @DefaultValue("50052") int sessionPort,
        @DefaultValue("localhost:50052") String sessionTarget,
        @DefaultValue("72h") Duration tokenTtl,
        @DefaultValue("365d") Duration deviceCertValidity,
        @DefaultValue("15s") Duration offlineAfter,
        @DefaultValue("true") boolean driftAutoRemediate,
        @DefaultValue("250ms") Duration dispatchInterval,
        @DefaultValue("500ms") Duration rolloutTick) {
}
