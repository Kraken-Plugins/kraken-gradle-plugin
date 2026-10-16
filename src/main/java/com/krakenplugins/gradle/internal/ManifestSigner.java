package com.krakenplugins.gradle.internal;

import groovy.json.JsonOutput;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signs repository manifests the way the Kraken client verifies them: the manifest bytes as base64, an Ed25519
 * signature over those bytes, and the signer's public key as a base64 X.509 SubjectPublicKeyInfo, together in one JSON
 * envelope. Keys are PKCS#8 PEM files, the format {@code openssl genpkey -algorithm ed25519} writes.
 *
 * <p>Ed25519 needs Java 15 or newer at runtime. The plugin itself compiles for Java 11, so only signing fails on older
 * JDKs.
 */
public final class ManifestSigner {

    private static final String ED25519 = "Ed25519";
    private static final int SEED_LENGTH = 32;
    // An OCTET STRING of 34 bytes wrapping an OCTET STRING of 32: the CurvePrivateKey inside a PKCS#8 Ed25519 key.
    private static final byte[] SEED_PREFIX = {0x04, 0x22, 0x04, 0x20};

    private ManifestSigner() {
    }

    /**
     * Signs a manifest and checks the signature with the key's public half before returning it.
     *
     * @param manifest      the manifest JSON bytes
     * @param privateKeyPem the Ed25519 private key as PKCS#8 PEM
     * @return the signed envelope
     * @throws GeneralSecurityException when the key isn't an Ed25519 private key, or the signature doesn't verify
     */
    public static Signed sign(byte[] manifest, String privateKeyPem) throws GeneralSecurityException {
        byte[] encoded = decodePem(privateKeyPem);
        PrivateKey privateKey = keyFactory().generatePrivate(new PKCS8EncodedKeySpec(encoded));
        PublicKey publicKey = publicKeyOf(encoded);

        Signature signer = Signature.getInstance(ED25519);
        signer.initSign(privateKey);
        signer.update(manifest);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance(ED25519);
        verifier.initVerify(publicKey);
        verifier.update(manifest);
        if (!verifier.verify(signature)) {
            throw new GeneralSecurityException("The signature made with the signing key does not verify with its public key.");
        }

        Map<String, String> envelope = new LinkedHashMap<>();
        envelope.put("signedManifest", Base64.getEncoder().encodeToString(manifest));
        envelope.put("signature", Base64.getEncoder().encodeToString(signature));
        envelope.put("publicKey", Base64.getEncoder().encodeToString(publicKey.getEncoded()));
        String json = JsonOutput.prettyPrint(JsonOutput.toJson(envelope)) + "\n";
        return new Signed(json.getBytes(StandardCharsets.UTF_8), fingerprint(publicKey.getEncoded()));
    }

    /**
     * Generates a new Ed25519 key pair.
     *
     * @return the private key as PKCS#8 PEM and the public key's fingerprint
     * @throws GeneralSecurityException when the JDK has no Ed25519 support
     */
    public static GeneratedKey generate() throws GeneralSecurityException {
        KeyPair keyPair = generator(null).generateKeyPair();
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
        return new GeneratedKey(pem, fingerprint(keyPair.getPublic().getEncoded()));
    }

    /**
     * The fingerprint the Kraken client shows for a repository's key: the first 16 hex digits of the SHA-256 of the
     * public key's SubjectPublicKeyInfo, in groups of four.
     *
     * @param publicKeyDer the public key's X.509 encoding
     * @return the fingerprint, e.g. {@code 3F2A 91C0 7B44 0E1D}
     */
    static String fingerprint(byte[] publicKeyDer) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(publicKeyDer);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0 && i % 2 == 0) {
                out.append(' ');
            }
            out.append(String.format("%02X", digest[i]));
        }
        return out.toString();
    }

    /**
     * Derives the public key from a PKCS#8 Ed25519 key. The JDK has no API for this, but its key pair generator turns 32
     * random bytes into a key pair, so feeding it the private key's seed as its randomness yields the matching public key.
     */
    private static PublicKey publicKeyOf(byte[] pkcs8) throws GeneralSecurityException {
        byte[] seed = null;
        for (int i = 0; i + SEED_PREFIX.length + SEED_LENGTH <= pkcs8.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(pkcs8, i, i + SEED_PREFIX.length), SEED_PREFIX)) {
                seed = Arrays.copyOfRange(pkcs8, i + SEED_PREFIX.length, i + SEED_PREFIX.length + SEED_LENGTH);
                break;
            }
        }
        if (seed == null) {
            throw new GeneralSecurityException("The signing key is not an Ed25519 private key.");
        }
        return generator(seed).generateKeyPair().getPublic();
    }

    private static KeyPairGenerator generator(byte[] seed) throws GeneralSecurityException {
        KeyPairGenerator generator;
        try {
            generator = KeyPairGenerator.getInstance(ED25519);
        } catch (NoSuchAlgorithmException e) {
            throw unsupported(e);
        }
        SecureRandom random = seed == null ? new SecureRandom() : new SeedRandom(seed);
        generator.initialize(new NamedParameterSpec(ED25519), random);
        return generator;
    }

    private static KeyFactory keyFactory() throws GeneralSecurityException {
        try {
            return KeyFactory.getInstance(ED25519);
        } catch (NoSuchAlgorithmException e) {
            throw unsupported(e);
        }
    }

    private static GeneralSecurityException unsupported(NoSuchAlgorithmException e) {
        return new GeneralSecurityException("Ed25519 needs Java 15 or newer, but Gradle runs on Java "
                + System.getProperty("java.version") + ". Run Gradle with Java 17.", e);
    }

    private static byte[] decodePem(String pem) throws GeneralSecurityException {
        String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        if (pem.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
            throw new GeneralSecurityException("The signing key is encrypted. Use an unencrypted PKCS#8 key (openssl genpkey -algorithm ed25519).");
        }
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("The signing key is not a PEM private key.", e);
        }
    }

    /**
     * A {@link SecureRandom} that returns a fixed seed, so the Ed25519 key pair generator rebuilds a known key.
     */
    private static final class SeedRandom extends SecureRandom {

        private static final long serialVersionUID = 1L;
        private final byte[] seed;

        SeedRandom(byte[] seed) {
            this.seed = seed;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (bytes.length != seed.length) {
                throw new IllegalStateException("Expected a request for " + seed.length + " bytes, got " + bytes.length);
            }
            System.arraycopy(seed, 0, bytes, 0, seed.length);
        }
    }

    /**
     * A signed manifest envelope and the fingerprint of the key that signed it.
     */
    public static final class Signed {

        private final byte[] envelope;
        private final String fingerprint;

        Signed(byte[] envelope, String fingerprint) {
            this.envelope = envelope;
            this.fingerprint = fingerprint;
        }

        public byte[] getEnvelope() {
            return envelope;
        }

        public String getFingerprint() {
            return fingerprint;
        }
    }

    /**
     * A newly generated private key and its public key's fingerprint.
     */
    public static final class GeneratedKey {

        private final String privateKeyPem;
        private final String fingerprint;

        GeneratedKey(String privateKeyPem, String fingerprint) {
            this.privateKeyPem = privateKeyPem;
            this.fingerprint = fingerprint;
        }

        public String getPrivateKeyPem() {
            return privateKeyPem;
        }

        public String getFingerprint() {
            return fingerprint;
        }
    }
}
