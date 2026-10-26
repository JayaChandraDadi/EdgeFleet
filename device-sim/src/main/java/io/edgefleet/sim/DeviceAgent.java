package io.edgefleet.sim;

import com.google.protobuf.ByteString;
import io.edgefleet.config.ConfigValidator;
import io.edgefleet.config.Configs;
import io.edgefleet.control.v1.BootstrapGrpc;
import io.edgefleet.control.v1.ClaimRequest;
import io.edgefleet.control.v1.ClaimResponse;
import io.edgefleet.control.v1.ConfigPush;
import io.edgefleet.control.v1.ConfigResult;
import io.edgefleet.control.v1.ControlSessionGrpc;
import io.edgefleet.control.v1.ControllerMessage;
import io.edgefleet.control.v1.DeviceMessage;
import io.edgefleet.control.v1.Heartbeat;
import io.edgefleet.control.v1.Hello;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One simulated edge router.
 *
 * <ol>
 *   <li>{@link #claim()}: generates its own EC key pair, sends a CSR with its serial
 *       and one-time bootstrap token, and receives its identity certificate. The
 *       private key never leaves the device.</li>
 *   <li>{@link #connect()}: opens the control session over mutual TLS and sends Hello
 *       with what it runs, then heartbeats.</li>
 *   <li>Pushes are applied with confirm semantics: apply, run the health check, and
 *       revert on failure, so a bad config cannot cut the device off. Pushes older
 *       than the running version are ignored; a re-delivered push of the running
 *       version is acknowledged again without reapplying.</li>
 * </ol>
 *
 * The simulated health check fails when any transport MTU is below
 * {@link Behavior#minHealthyMtu()}: with IPsec overhead, such tunnels fragment and
 * BFD sessions drop, which is a realistic way for a valid-looking config to break.
 */
public final class DeviceAgent implements AutoCloseable {

    /**
     * @param applyDelayMs     min/max time to apply a config
     * @param heartbeatMs      heartbeat interval
     * @param minHealthyMtu    smallest MTU at which tunnels come up
     * @param randomFailurePct chance any apply fails its health check anyway
     */
    public record Behavior(int[] applyDelayMs, long heartbeatMs, int minHealthyMtu, double randomFailurePct) {
        public static Behavior defaults() {
            return new Behavior(new int[] {50, 250}, 5_000, 1300, 0);
        }
    }

    /** Outcome of the claim, kept for inspection. */
    public record Identity(X509Certificate certificate, String systemIp, int siteId, String hostname,
                           String sessionTarget) {
    }

    private final String serial;
    private final String model;
    private final String bootstrapToken;
    private final X509Certificate trustAnchor;
    private final String bootstrapTarget;
    private final Behavior behavior;
    private final ScheduledExecutorService timers;
    private final ExecutorService applier = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

    private KeyPair keyPair;
    private Identity identity;
    private ManagedChannel sessionChannel;
    private volatile StreamObserver<DeviceMessage> session;
    private ScheduledFuture<?> heartbeat;

    // Running state: guarded by `this`.
    private long appliedVersion;
    private JsonNode runningConfig;
    private String runningHash = "";
    private volatile boolean healthy = true;

    private final AtomicReference<Throwable> sessionError = new AtomicReference<>();
    private volatile boolean sessionOpen;

    public DeviceAgent(String serial, String model, String bootstrapToken, X509Certificate trustAnchor,
                       String bootstrapTarget, Behavior behavior, ScheduledExecutorService timers) {
        this.serial = serial;
        this.model = model;
        this.bootstrapToken = bootstrapToken;
        this.trustAnchor = trustAnchor;
        this.bootstrapTarget = bootstrapTarget;
        this.behavior = behavior;
        this.timers = timers;
    }

    /** Zero-touch claim. Throws {@link StatusRuntimeException} if the controller refuses. */
    public Identity claim() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            keyPair = g.generateKeyPair();
            byte[] csr = new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=" + serial), keyPair.getPublic())
                    .build(new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate()))
                    .getEncoded();
            ManagedChannel ch = NettyChannelBuilder.forTarget(bootstrapTarget)
                    .sslContext(GrpcSslContexts.forClient().trustManager(trustAnchor).build())
                    .build();
            try {
                ClaimResponse r = BootstrapGrpc.newBlockingStub(ch).withDeadlineAfter(30, TimeUnit.SECONDS)
                        .claim(ClaimRequest.newBuilder()
                                .setSerial(serial).setModel(model).setBootstrapToken(bootstrapToken)
                                .setCsrDer(ByteString.copyFrom(csr)).build());
                X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(r.getCertificateDer().toByteArray()));
                identity = new Identity(cert, r.getSystemIp(), r.getSiteId(), r.getHostname(), r.getSessionTarget());
                return identity;
            } finally {
                ch.shutdownNow();
            }
        } catch (GeneralSecurityException | OperatorCreationException | java.io.IOException e) {
            throw new IllegalStateException("claim failed for " + serial, e);
        }
    }

    /** Opens the mTLS control session (after {@link #claim()}). */
    public synchronized void connect() {
        if (identity == null) {
            throw new IllegalStateException("claim first");
        }
        disconnect(); // drop any previous session first; reconnects reuse the same identity
        try {
            SslContext tls = GrpcSslContexts.forClient()
                    .trustManager(trustAnchor)
                    .keyManager(keyPair.getPrivate(), identity.certificate())
                    .build();
            sessionChannel = NettyChannelBuilder.forTarget(identity.sessionTarget()).sslContext(tls)
                    .keepAliveTime(30, TimeUnit.SECONDS).build();
        } catch (SSLException e) {
            throw new IllegalStateException(e);
        }
        sessionError.set(null);
        sessionOpen = true;
        session = ControlSessionGrpc.newStub(sessionChannel).connect(new StreamObserver<>() {
            @Override
            public void onNext(ControllerMessage m) {
                if (m.hasPush()) {
                    ConfigPush push = m.getPush();
                    applier.execute(() -> handlePush(push));
                }
            }

            @Override
            public void onError(Throwable t) {
                sessionError.set(t);
                sessionOpen = false;
            }

            @Override
            public void onCompleted() {
                sessionOpen = false;
            }
        });
        send(DeviceMessage.newBuilder().setHello(Hello.newBuilder()
                .setAppliedVersion(appliedVersion()).setRunningHash(runningHash()).setSoftwareVersion("17.15.1")).build());
        heartbeat = timers.scheduleAtFixedRate(this::sendHeartbeat,
                ThreadLocalRandom.current().nextLong(behavior.heartbeatMs()), behavior.heartbeatMs(),
                TimeUnit.MILLISECONDS);
    }

    public void sendHeartbeat() {
        if (sessionOpen) {
            send(DeviceMessage.newBuilder().setHeartbeat(Heartbeat.newBuilder()
                    .setAppliedVersion(appliedVersion()).setRunningHash(runningHash()).setHealthy(healthy)).build());
        }
    }

    private void handlePush(ConfigPush push) {
        ConfigResult.Builder r = ConfigResult.newBuilder().setCommandId(push.getCommandId()).setVersion(push.getVersion());
        long prevVersion;
        synchronized (this) {
            prevVersion = appliedVersion;
            if (push.getVersion() <= appliedVersion) {
                boolean same = push.getVersion() == appliedVersion && push.getConfigHash().equals(runningHash);
                finish(r.setStatus(same ? ConfigResult.Status.APPLIED_HEALTHY : ConfigResult.Status.STALE_VERSION)
                        .setDetail(same ? "already running this version" : "running v" + appliedVersion));
                return;
            }
        }
        JsonNode config;
        try {
            config = Configs.parse(push.getConfigJson());
        } catch (IllegalArgumentException e) {
            finish(r.setStatus(ConfigResult.Status.REJECTED).setDetail("config is not valid JSON"));
            return;
        }
        if (!Configs.hash(config).equals(push.getConfigHash())) {
            finish(r.setStatus(ConfigResult.Status.REJECTED).setDetail("hash mismatch: config corrupted in transit"));
            return;
        }
        List<ConfigValidator.Violation> v = ConfigValidator.validate(config);
        if (!v.isEmpty()) {
            finish(r.setStatus(ConfigResult.Status.REJECTED).setDetail("invalid config: " + v.getFirst()));
            return;
        }

        sleep(ThreadLocalRandom.current().nextInt(behavior.applyDelayMs()[0], behavior.applyDelayMs()[1] + 1));
        String problem = healthProblem(config);
        if (problem == null && ThreadLocalRandom.current().nextDouble() * 100 < behavior.randomFailurePct()) {
            problem = "control connections did not come up";
        }
        synchronized (this) {
            if (problem == null) {
                appliedVersion = push.getVersion();
                runningConfig = config;
                runningHash = push.getConfigHash();
                healthy = true;
                finish(r.setStatus(ConfigResult.Status.APPLIED_HEALTHY));
            } else {
                // Confirm timer expired: the previous config is still running.
                finish(r.setStatus(ConfigResult.Status.HEALTH_CHECK_FAILED)
                        .setDetail(problem + "; reverted to v" + prevVersion));
            }
        }
    }

    private void finish(ConfigResult.Builder r) {
        synchronized (this) {
            r.setRunningVersion(appliedVersion).setRunningHash(runningHash);
        }
        send(DeviceMessage.newBuilder().setResult(r).build());
    }

    private String healthProblem(JsonNode config) {
        for (JsonNode t : config.get("transports").values()) {
            if (t.get("mtu").asInt() < behavior.minHealthyMtu()) {
                return "BFD down on " + t.get("color").asString() + ": MTU " + t.get("mtu").asInt()
                        + " fragments IPsec tunnel packets";
            }
        }
        return null;
    }

    /** Simulates an out-of-band change (someone edited the config on the CLI). */
    public synchronized void editLocally(String banner) {
        if (runningConfig == null) {
            return;
        }
        ObjectNode edited = (ObjectNode) runningConfig.deepCopy();
        edited.put("banner", banner);
        runningConfig = edited;
        runningHash = Configs.hash(edited);
    }

    private synchronized void send(DeviceMessage m) {
        StreamObserver<DeviceMessage> s = session;
        if (s != null && sessionOpen) {
            try {
                s.onNext(m);
            } catch (RuntimeException e) {
                sessionOpen = false;
            }
        }
    }

    /** Drops the session without telling the controller (pulled cable). */
    public synchronized void disconnect() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        sessionOpen = false;
        if (sessionChannel != null) {
            sessionChannel.shutdownNow();
        }
    }

    @Override
    public void close() {
        disconnect();
        applier.shutdownNow();
    }

    public synchronized long appliedVersion() {
        return appliedVersion;
    }

    public synchronized String runningHash() {
        return runningHash;
    }

    public synchronized JsonNode runningConfig() {
        return runningConfig;
    }

    public boolean sessionOpen() {
        return sessionOpen;
    }

    public Throwable sessionError() {
        return sessionError.get();
    }

    public Identity identity() {
        return identity;
    }

    public String serial() {
        return serial;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
