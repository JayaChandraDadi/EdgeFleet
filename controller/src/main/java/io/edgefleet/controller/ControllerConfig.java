package io.edgefleet.controller;

import io.edgefleet.controller.device.InventoryService;
import io.edgefleet.controller.device.OnboardingService;
import io.edgefleet.controller.pki.CertificateAuthority;
import io.edgefleet.controller.session.BootstrapGrpcService;
import io.edgefleet.controller.session.CommandDispatcher;
import io.edgefleet.controller.session.ControlSessionGrpcService;
import io.edgefleet.controller.session.DeviceIdentityInterceptor;
import io.edgefleet.controller.session.DeviceStateService;
import io.edgefleet.controller.session.GrpcServers;
import io.edgefleet.controller.session.SessionRegistry;
import io.grpc.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
public class ControllerConfig {

    @Bean
    CertificateAuthority certificateAuthority(EdgeFleetProperties props) {
        return CertificateAuthority.loadOrCreate(props.pkiDir());
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistry();
    }

    @Bean
    BootstrapGrpcService bootstrapGrpcService(OnboardingService onboarding, EdgeFleetProperties props) {
        return new BootstrapGrpcService(onboarding, props);
    }

    @Bean
    ControlSessionGrpcService controlSessionGrpcService(SessionRegistry registry, DeviceStateService state,
                                                        CommandDispatcher dispatcher) {
        return new ControlSessionGrpcService(registry, state, dispatcher);
    }

    @Bean
    DeviceIdentityInterceptor deviceIdentityInterceptor(JdbcClient jdbc) {
        return new DeviceIdentityInterceptor(jdbc);
    }

    @Bean
    GrpcServers grpcServers(EdgeFleetProperties props, CertificateAuthority ca, BootstrapGrpcService bootstrap,
                            ControlSessionGrpcService sessions, DeviceIdentityInterceptor identity) {
        return new GrpcServers(props, ca, bootstrap, sessions, identity);
    }

    /** Drops a decommissioned device's live session at once. */
    @Bean
    DecommissionListener decommissionListener(SessionRegistry registry) {
        return new DecommissionListener(registry);
    }

    public static final class DecommissionListener {
        private final SessionRegistry registry;

        DecommissionListener(SessionRegistry registry) {
            this.registry = registry;
        }

        @org.springframework.transaction.event.TransactionalEventListener(
                phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
        public void on(InventoryService.Decommissioned e) {
            registry.close(e.serial(), Status.PERMISSION_DENIED.withDescription("device decommissioned"));
        }
    }
}
