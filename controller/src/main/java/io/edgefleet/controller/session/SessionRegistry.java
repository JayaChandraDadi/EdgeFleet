package io.edgefleet.controller.session;

import io.edgefleet.control.v1.ConfigPush;
import io.edgefleet.control.v1.ControllerMessage;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Control sessions held by this controller instance, one per device. A device that
 * reconnects replaces its old session (for example after a NAT rebinding left the
 * old TCP connection half-open).
 */
public class SessionRegistry {

    /** One device's live stream. Sends are serialized: gRPC observers are not thread-safe. */
    public static final class Session {
        private static final AtomicLong IDS = new AtomicLong();

        final long id = IDS.incrementAndGet();
        final String serial;
        private final ServerCallStreamObserver<ControllerMessage> out;
        private volatile boolean closed;

        Session(String serial, ServerCallStreamObserver<ControllerMessage> out) {
            this.serial = serial;
            this.out = out;
        }

        public synchronized boolean send(ConfigPush push) {
            if (closed || out.isCancelled()) {
                return false;
            }
            out.onNext(ControllerMessage.newBuilder().setPush(push).build());
            return true;
        }

        synchronized void close(Status status) {
            if (!closed) {
                closed = true;
                if (!out.isCancelled()) {
                    out.onError(status.asRuntimeException());
                }
            }
        }

        public boolean isClosed() {
            return closed || out.isCancelled();
        }
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    Session register(String serial, ServerCallStreamObserver<ControllerMessage> out) {
        Session s = new Session(serial, out);
        Session old = sessions.put(serial, s);
        if (old != null) {
            old.close(Status.ABORTED.withDescription("replaced by a newer session"));
        }
        return s;
    }

    /** Removes the session if it is still the current one; returns whether it was. */
    boolean unregister(Session s) {
        s.closed = true;
        return sessions.remove(s.serial, s);
    }

    public Optional<Session> get(String serial) {
        Session s = sessions.get(serial);
        return s == null || s.isClosed() ? Optional.empty() : Optional.of(s);
    }

    public Set<String> connectedSerials() {
        return Set.copyOf(sessions.keySet());
    }

    public void close(String serial, Status status) {
        Session s = sessions.remove(serial);
        if (s != null) {
            s.close(status);
        }
    }

    public int size() {
        return sessions.size();
    }
}
