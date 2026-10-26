package io.edgefleet.controller.session;

import io.edgefleet.controller.EdgeFleetProperties;
import io.edgefleet.controller.pki.CertificateAuthority;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.protobuf.services.HealthStatusManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Two TLS listeners:
 * <ul>
 *   <li>bootstrap: server-authenticated TLS only, because claiming devices have no
 *       certificate yet;</li>
 *   <li>session: mutual TLS; a client certificate signed by the controller CA is
 *       required before any message is read.</li>
 * </ul>
 * Handlers run on virtual threads: each message does blocking JDBC work, and
 * thousands of mostly idle device sessions should not tie up platform threads.
 */
public class GrpcServers implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServers.class);

    private final EdgeFleetProperties props;
    private final CertificateAuthority ca;
    private final BootstrapGrpcService bootstrap;
    private final ControlSessionGrpcService sessions;
    private final DeviceIdentityInterceptor identity;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private Server bootstrapServer;
    private Server sessionServer;

    public GrpcServers(EdgeFleetProperties props, CertificateAuthority ca, BootstrapGrpcService bootstrap,
                       ControlSessionGrpcService sessions, DeviceIdentityInterceptor identity) {
        this.props = props;
        this.ca = ca;
        this.bootstrap = bootstrap;
        this.sessions = sessions;
        this.identity = identity;
    }

    @Override
    public void start() {
        Path[] server = ca.serverCertificate(props.tlsHostnames());
        try {
            SslContext bootstrapTls = GrpcSslContexts.forServer(server[0].toFile(), server[1].toFile()).build();
            SslContext sessionTls = GrpcSslContexts.forServer(server[0].toFile(), server[1].toFile())
                    .trustManager(ca.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build();
            bootstrapServer = NettyServerBuilder.forPort(props.bootstrapPort())
                    .sslContext(bootstrapTls)
                    .executor(executor)
                    .addService(bootstrap)
                    .build().start();
            sessionServer = NettyServerBuilder.forPort(props.sessionPort())
                    .sslContext(sessionTls)
                    .executor(executor)
                    .keepAliveTime(20, TimeUnit.SECONDS)
                    .keepAliveTimeout(10, TimeUnit.SECONDS)
                    .permitKeepAliveTime(5, TimeUnit.SECONDS)
                    .permitKeepAliveWithoutCalls(true)
                    .addService(ServerInterceptors.intercept(sessions, identity))
                    .addService(new HealthStatusManager().getHealthService())
                    .build().start();
            log.info("bootstrap (TLS) on :{}, control sessions (mTLS) on :{}",
                    bootstrapServer.getPort(), sessionServer.getPort());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void stop() {
        for (Server s : new Server[] {sessionServer, bootstrapServer}) {
            if (s != null) {
                s.shutdown();
                try {
                    if (!s.awaitTermination(5, TimeUnit.SECONDS)) {
                        s.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    s.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return sessionServer != null && !sessionServer.isShutdown();
    }

    public int bootstrapPort() {
        return bootstrapServer.getPort();
    }

    public int sessionPort() {
        return sessionServer.getPort();
    }
}
