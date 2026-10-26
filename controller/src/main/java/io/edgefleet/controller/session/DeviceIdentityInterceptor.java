package io.edgefleet.controller.session;

import io.edgefleet.controller.pki.CertificateAuthority;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Binds each control-session call to the device named in its TLS client certificate.
 *
 * <p>The TLS layer already verified the chain against the controller CA. On top of
 * that, the certificate must be the device's current one (re-claiming issues a new
 * certificate and retires the old) and the device must not be decommissioned. That
 * check runs on every new session, so revocation takes effect on the next connect
 * without a CRL; live sessions are closed when the device is decommissioned.
 */
public class DeviceIdentityInterceptor implements ServerInterceptor {

    public static final Context.Key<String> DEVICE_SERIAL = Context.key("device-serial");

    private final JdbcClient jdbc;

    public DeviceIdentityInterceptor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        X509Certificate cert = peerCertificate(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION));
        String serial = cert == null ? null : CertificateAuthority.deviceSerialOf(cert);
        if (serial == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("client certificate required"), new Metadata());
            return new ServerCall.Listener<>() { };
        }
        String certSerial = cert.getSerialNumber().toString(16);
        boolean current = jdbc.sql("""
                SELECT count(*) FROM devices WHERE serial = ? AND state = 'ONBOARDED' AND cert_serial = ?""")
                .params(serial, certSerial).query(Long.class).single() == 1;
        if (!current) {
            call.close(Status.PERMISSION_DENIED.withDescription("certificate revoked or device decommissioned"),
                    new Metadata());
            return new ServerCall.Listener<>() { };
        }
        return Contexts.interceptCall(Context.current().withValue(DEVICE_SERIAL, serial), call, headers, next);
    }

    private static X509Certificate peerCertificate(SSLSession session) {
        if (session == null) {
            return null;
        }
        try {
            Certificate[] chain = session.getPeerCertificates();
            return chain.length > 0 && chain[0] instanceof X509Certificate x ? x : null;
        } catch (SSLPeerUnverifiedException e) {
            return null;
        }
    }
}
