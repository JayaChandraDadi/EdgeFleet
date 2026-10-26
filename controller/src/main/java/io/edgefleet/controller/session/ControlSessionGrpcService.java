package io.edgefleet.controller.session;

import io.edgefleet.control.v1.ControlSessionGrpc;
import io.edgefleet.control.v1.ControllerMessage;
import io.edgefleet.control.v1.DeviceMessage;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One long-lived stream per device; the device's identity comes from its certificate. */
public class ControlSessionGrpcService extends ControlSessionGrpc.ControlSessionImplBase {

    private static final Logger log = LoggerFactory.getLogger(ControlSessionGrpcService.class);

    private final SessionRegistry registry;
    private final DeviceStateService state;
    private final CommandDispatcher dispatcher;

    public ControlSessionGrpcService(SessionRegistry registry, DeviceStateService state,
                                     CommandDispatcher dispatcher) {
        this.registry = registry;
        this.state = state;
        this.dispatcher = dispatcher;
    }

    @Override
    public StreamObserver<DeviceMessage> connect(StreamObserver<ControllerMessage> responses) {
        String serial = DeviceIdentityInterceptor.DEVICE_SERIAL.get();
        ServerCallStreamObserver<ControllerMessage> out = (ServerCallStreamObserver<ControllerMessage>) responses;
        SessionRegistry.Session session = registry.register(serial, out);

        return new StreamObserver<>() {
            private boolean helloSeen;

            @Override
            public void onNext(DeviceMessage m) {
                try {
                    switch (m.getMsgCase()) {
                        case HELLO -> {
                            helloSeen = true;
                            state.onHello(serial, m.getHello().getAppliedVersion(), m.getHello().getRunningHash());
                            dispatcher.dispatch(serial);
                        }
                        case HEARTBEAT -> {
                            if (helloSeen) {
                                var h = m.getHeartbeat();
                                state.onHeartbeat(serial, h.getAppliedVersion(), h.getRunningHash(), h.getHealthy());
                                dispatcher.dispatch(serial);
                            }
                        }
                        case RESULT -> {
                            state.onResult(serial, m.getResult());
                            dispatcher.dispatch(serial);
                        }
                        default -> { }
                    }
                } catch (RuntimeException e) {
                    log.warn("session {}: failed to handle {}: {}", serial, m.getMsgCase(), e.toString());
                    session.close(Status.INTERNAL.withDescription("controller error; reconnect"));
                }
            }

            @Override
            public void onError(Throwable t) {
                end();
            }

            @Override
            public void onCompleted() {
                end();
                out.onCompleted();
            }

            private void end() {
                if (registry.unregister(session)) {
                    state.onDisconnect(serial);
                }
            }
        };
    }
}
