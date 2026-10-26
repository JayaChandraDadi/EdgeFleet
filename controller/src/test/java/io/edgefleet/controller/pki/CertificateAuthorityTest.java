package io.edgefleet.controller.pki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CertificateAuthorityTest {

    @TempDir
    Path dir;

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static byte[] csr(String cn, KeyPair kp, String sigAlg) throws Exception {
        return new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=" + cn), kp.getPublic())
                .build(new JcaContentSignerBuilder(sigAlg).build(kp.getPrivate())).getEncoded();
    }

    @Test
    void createsCaOnceAndReloadsTheSameOne() {
        CertificateAuthority first = CertificateAuthority.loadOrCreate(dir);
        CertificateAuthority second = CertificateAuthority.loadOrCreate(dir);
        assertThat(second.caCertificate()).isEqualTo(first.caCertificate());
        assertThat(first.caCertificate().getBasicConstraints()).isZero(); // a CA that can sign leaves only
        assertThat(Files.exists(dir.resolve("ca.key"))).isTrue();
    }

    @Test
    void signsADeviceCsrIntoAClientOnlyIdentity() throws Exception {
        CertificateAuthority ca = CertificateAuthority.loadOrCreate(dir);
        X509Certificate cert = ca.signDeviceCsr(csr("FGL2231A0QX", ec(), "SHA256withECDSA"), "FGL2231A0QX",
                Duration.ofDays(30));
        cert.verify(ca.caCertificate().getPublicKey());
        assertThat(CertificateAuthority.deviceSerialOf(cert)).isEqualTo("FGL2231A0QX");
        assertThat(cert.getExtendedKeyUsage()).containsExactly("1.3.6.1.5.5.7.3.2");
        assertThat(cert.getBasicConstraints()).isEqualTo(-1); // not a CA
        assertThat(cert.getSubjectAlternativeNames()).anySatisfy(san ->
                assertThat(san.get(1)).isEqualTo("urn:edgefleet:device:FGL2231A0QX"));
    }

    @Test
    void refusesACsrForAnotherSerial() throws Exception {
        CertificateAuthority ca = CertificateAuthority.loadOrCreate(dir);
        assertThatThrownBy(() -> ca.signDeviceCsr(csr("OTHER-SERIAL", ec(), "SHA256withECDSA"), "FGL2231A0QX",
                Duration.ofDays(30)))
                .isInstanceOf(CertificateAuthority.CsrRejectedException.class)
                .hasMessageContaining("CN");
    }

    @Test
    void refusesATamperedCsr() throws Exception {
        CertificateAuthority ca = CertificateAuthority.loadOrCreate(dir);
        byte[] der = csr("FGL2231A0QX", ec(), "SHA256withECDSA");
        der[der.length - 5] ^= 0x01; // flip a bit inside the signature
        assertThatThrownBy(() -> ca.signDeviceCsr(der, "FGL2231A0QX", Duration.ofDays(30)))
                .isInstanceOf(CertificateAuthority.CsrRejectedException.class);
    }

    @Test
    void refusesWeakRsaKeys() throws Exception {
        CertificateAuthority ca = CertificateAuthority.loadOrCreate(dir);
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        assertThatThrownBy(() -> ca.signDeviceCsr(csr("FGL2231A0QX", g.generateKeyPair(), "SHA256withRSA"),
                "FGL2231A0QX", Duration.ofDays(30)))
                .isInstanceOf(CertificateAuthority.CsrRejectedException.class)
                .hasMessageContaining("2048");
    }

    @Test
    void serverCertificateCoversTheConfiguredNames() throws Exception {
        CertificateAuthority ca = CertificateAuthority.loadOrCreate(dir);
        Path[] paths = ca.serverCertificate(List.of("localhost", "127.0.0.1", "controller.edgefleet.local"));
        X509Certificate cert = CertificateAuthority.readCert(Files.readString(paths[0]));
        List<String> names = cert.getSubjectAlternativeNames().stream().map(san -> String.valueOf(san.get(1))).toList();
        assertThat(names).contains("localhost", "127.0.0.1", "controller.edgefleet.local");
        assertThat(cert.getExtendedKeyUsage()).containsExactly("1.3.6.1.5.5.7.3.1");
    }
}
