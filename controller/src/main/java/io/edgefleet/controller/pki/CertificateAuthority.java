package io.edgefleet.controller.pki;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The controller's own certificate authority (EC P-256).
 *
 * <p>It issues two kinds of certificates: a server certificate for the controller's
 * TLS endpoints, and one identity certificate per device, signed from a CSR the
 * device generated itself. Device certificates carry the serial number as CN and as
 * a {@code urn:edgefleet:device:<serial>} URI SAN and are valid for client
 * authentication only.
 *
 * <p>Keys are kept as PEM files in a directory and created on first start. A
 * production deployment would keep the CA key in an HSM or a secrets manager.
 */
public final class CertificateAuthority {

    private static final Logger log = LoggerFactory.getLogger(CertificateAuthority.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SIG = "SHA256withECDSA";

    /** CSR rejected; the message is safe to return to the device. */
    public static final class CsrRejectedException extends RuntimeException {
        public CsrRejectedException(String message) {
            super(message);
        }
    }

    private final PrivateKey caKey;
    private final X509Certificate caCert;
    private final Path dir;

    private CertificateAuthority(Path dir, PrivateKey caKey, X509Certificate caCert) {
        this.dir = dir;
        this.caKey = caKey;
        this.caCert = caCert;
    }

    /** Loads the CA from {@code dir}, creating a new one if none exists. */
    public static CertificateAuthority loadOrCreate(Path dir) {
        try {
            Files.createDirectories(dir);
            Path key = dir.resolve("ca.key");
            Path crt = dir.resolve("ca.crt");
            if (Files.exists(key) && Files.exists(crt)) {
                return new CertificateAuthority(dir, readKey(Files.readString(key)), readCert(Files.readString(crt)));
            }
            KeyPair kp = newKeyPair();
            X500Name name = new X500Name("CN=EdgeFleet Root CA,O=EdgeFleet");
            Instant now = Instant.now();
            X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, serial(),
                    Date.from(now.minus(Duration.ofMinutes(5))), Date.from(now.plus(Duration.ofDays(3650))),
                    name, kp.getPublic());
            JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
            b.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
            b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(kp.getPublic()));
            X509Certificate cert = sign(b, kp.getPrivate());
            writePem(key, pkcs8(kp.getPrivate()));
            writePem(crt, pem(cert));
            log.info("created new CA in {}", dir);
            return new CertificateAuthority(dir, kp.getPrivate(), cert);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException | OperatorCreationException e) {
            throw new IllegalStateException("cannot create CA", e);
        }
    }

    public X509Certificate caCertificate() {
        return caCert;
    }

    public Path caCertificatePath() {
        return dir.resolve("ca.crt");
    }

    /**
     * Issues (or reuses) the controller's TLS server certificate for the given names
     * and returns the paths of {@code server.crt} and {@code server.key}.
     */
    public Path[] serverCertificate(List<String> hostnames) {
        try {
            Path crt = dir.resolve("server.crt");
            Path key = dir.resolve("server.key");
            if (Files.exists(crt) && Files.exists(key)) {
                X509Certificate existing = readCert(Files.readString(crt));
                if (existing.getNotAfter().toInstant().isAfter(Instant.now().plus(Duration.ofDays(30)))
                        && existing.getIssuerX500Principal().equals(caCert.getSubjectX500Principal())) {
                    return new Path[] {crt, key};
                }
            }
            KeyPair kp = newKeyPair();
            List<GeneralName> sans = new ArrayList<>();
            for (String h : hostnames) {
                boolean ip = h.matches("^[0-9.]+$") || h.contains(":");
                sans.add(ip ? new GeneralName(GeneralName.iPAddress, InetAddress.getByName(h).getHostAddress())
                        : new GeneralName(GeneralName.dNSName, h));
            }
            X509Certificate cert = issue(new X500Name("CN=edgefleet-controller,O=EdgeFleet"), kp.getPublic(),
                    Duration.ofDays(397), KeyPurposeId.id_kp_serverAuth, sans);
            writePem(key, pkcs8(kp.getPrivate()));
            writePem(crt, pem(cert) + pem(caCert));
            return new Path[] {crt, key};
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException | OperatorCreationException e) {
            throw new IllegalStateException("cannot issue server certificate", e);
        }
    }

    /**
     * Signs a device CSR. The CSR must be self-consistent (its signature verifies
     * with the key inside it), name exactly the expected serial as CN, and use an
     * EC key of at least 256 bits or an RSA key of at least 2048 bits.
     */
    public X509Certificate signDeviceCsr(byte[] csrDer, String expectedSerial, Duration validity) {
        PKCS10CertificationRequest csr;
        PublicKey publicKey;
        try {
            csr = new PKCS10CertificationRequest(csrDer);
            publicKey = new JcaPKCS10CertificationRequest(csr).getPublicKey();
            if (!csr.isSignatureValid(new JcaContentVerifierProviderBuilder().build(csr.getSubjectPublicKeyInfo()))) {
                throw new CsrRejectedException("CSR signature does not verify");
            }
        } catch (IOException | IllegalArgumentException | ClassCastException e) {
            throw new CsrRejectedException("CSR is not a valid PKCS#10 request");
        } catch (GeneralSecurityException | OperatorCreationException | PKCSException e) {
            throw new CsrRejectedException("CSR signature does not verify");
        }

        RDN[] cns = csr.getSubject().getRDNs(BCStyle.CN);
        String cn = cns.length == 1 ? IETFUtils.valueToString(cns[0].getFirst().getValue()) : null;
        if (!expectedSerial.equals(cn)) {
            throw new CsrRejectedException("CSR subject CN must equal the device serial");
        }
        if (publicKey instanceof ECPublicKey ec && ec.getParams().getCurve().getField().getFieldSize() < 256) {
            throw new CsrRejectedException("EC keys must be at least 256 bits");
        }
        if (publicKey instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() < 2048) {
            throw new CsrRejectedException("RSA keys must be at least 2048 bits");
        }
        if (!(publicKey instanceof ECPublicKey) && !(publicKey instanceof RSAPublicKey)) {
            throw new CsrRejectedException("only EC and RSA keys are accepted");
        }
        try {
            return issue(new X500Name("CN=" + expectedSerial + ",O=EdgeFleet Devices"), publicKey, validity,
                    KeyPurposeId.id_kp_clientAuth,
                    List.of(new GeneralName(GeneralName.uniformResourceIdentifier,
                            "urn:edgefleet:device:" + expectedSerial)));
        } catch (GeneralSecurityException | OperatorCreationException | IOException e) {
            throw new IllegalStateException("cannot sign device certificate", e);
        }
    }

    /** Serial number of the device a client certificate belongs to (its CN). */
    public static String deviceSerialOf(X509Certificate cert) {
        X500Name subject = X500Name.getInstance(cert.getSubjectX500Principal().getEncoded());
        RDN[] cns = subject.getRDNs(BCStyle.CN);
        return cns.length == 1 ? IETFUtils.valueToString(cns[0].getFirst().getValue()) : null;
    }

    private X509Certificate issue(X500Name subject, PublicKey key, Duration validity, KeyPurposeId purpose,
                                  List<GeneralName> sans)
            throws GeneralSecurityException, OperatorCreationException, IOException {
        Instant now = Instant.now();
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(caCert, serial(),
                Date.from(now.minus(Duration.ofMinutes(5))), Date.from(now.plus(validity)), subject, key);
        JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyAgreement));
        b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(purpose));
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(key));
        b.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(caCert));
        if (!sans.isEmpty()) {
            b.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(sans.toArray(GeneralName[]::new)));
        }
        return sign(b, caKey);
    }

    private static X509Certificate sign(X509v3CertificateBuilder b, PrivateKey key)
            throws OperatorCreationException, GeneralSecurityException {
        ContentSigner signer = new JcaContentSignerBuilder(SIG).build(key);
        X509CertificateHolder holder = b.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return g.generateKeyPair();
    }

    private static BigInteger serial() {
        return new BigInteger(127, RANDOM).add(BigInteger.ONE);
    }

    public static String pem(X509Certificate cert) {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            w.writeObject(cert);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sw.toString();
    }

    private static String pkcs8(PrivateKey key) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            w.writeObject(new JcaPKCS8Generator(key, null));
        }
        return sw.toString();
    }

    private static void writePem(Path path, String pem) throws IOException {
        Files.writeString(path, pem);
    }

    static X509Certificate readCert(String pem) throws IOException, GeneralSecurityException {
        try (PEMParser p = new PEMParser(new StringReader(pem))) {
            return new JcaX509CertificateConverter().getCertificate((X509CertificateHolder) p.readObject());
        }
    }

    static PrivateKey readKey(String pem) throws IOException {
        try (PEMParser p = new PEMParser(new StringReader(pem))) {
            return new JcaPEMKeyConverter().getPrivateKey((PrivateKeyInfo) p.readObject());
        }
    }
}
