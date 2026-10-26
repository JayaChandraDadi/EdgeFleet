package io.edgefleet.controller.session;

import io.edgefleet.control.v1.BootstrapGrpc;
import io.edgefleet.control.v1.ClaimRequest;
import io.edgefleet.control.v1.ClaimResponse;
import io.edgefleet.controller.EdgeFleetProperties;
import io.edgefleet.controller.device.OnboardingService;
import io.edgefleet.controller.pki.CertificateAuthority;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.security.cert.CertificateEncodingException;

public class BootstrapGrpcService extends BootstrapGrpc.BootstrapImplBase {

    private final OnboardingService onboarding;
    private final EdgeFleetProperties props;

    public BootstrapGrpcService(OnboardingService onboarding, EdgeFleetProperties props) {
        this.onboarding = onboarding;
        this.props = props;
    }

    @Override
    public void claim(ClaimRequest req, StreamObserver<ClaimResponse> out) {
        try {
            OnboardingService.Claimed c = onboarding.claim(req.getSerial(), req.getModel(),
                    req.getBootstrapToken(), req.getCsrDer().toByteArray());
            out.onNext(ClaimResponse.newBuilder()
                    .setCertificateDer(com.google.protobuf.ByteString.copyFrom(c.certificate().getEncoded()))
                    .setSystemIp(c.systemIp())
                    .setSiteId((int) c.siteId())
                    .setHostname(c.hostname())
                    .setSessionTarget(props.sessionTarget())
                    .build());
            out.onCompleted();
        } catch (OnboardingService.ClaimDeniedException e) {
            out.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
        } catch (CertificateAuthority.CsrRejectedException e) {
            out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (CertificateEncodingException e) {
            out.onError(Status.INTERNAL.withDescription("cannot encode certificate").asRuntimeException());
        } catch (RuntimeException e) {
            // Overload or a database hiccup: nothing was committed, so the token is still
            // valid. UNAVAILABLE tells the device to retry with backoff.
            out.onError(Status.UNAVAILABLE.withDescription("controller busy; retry").asRuntimeException());
        }
    }
}
