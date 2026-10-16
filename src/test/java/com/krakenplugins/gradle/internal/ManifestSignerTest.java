package com.krakenplugins.gradle.internal;

import groovy.json.JsonSlurper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestSignerTest {

    // A test-only key made with: openssl genpkey -algorithm ed25519
    private static final String OPENSSL_KEY = "-----BEGIN PRIVATE KEY-----\n"
            + "MC4CAQAwBQYDK2VwBCIEIOjIK/mrWzM1u3vRtrncWNuLE3rKdJy2g98r79OO2Z4k\n"
            + "-----END PRIVATE KEY-----\n";
    // openssl pkey -in key.pem -pubout -outform DER | base64
    private static final String OPENSSL_PUBLIC_KEY = "MCowBQYDK2VwAyEAz68fQ7GxreFTUtYXDQbd7dvr+GD0ty0dUaYgYt7aKYs=";
    // printf hello | openssl pkeyutl -sign -inkey key.pem -rawin | base64
    private static final String OPENSSL_SIGNATURE_OF_HELLO =
            "7o4bBctVOHjyYWWN2RT257loVEAqxbqsCInBs1aVU/IgQiNFTVrVsMXi/Y8BRjQ2U0bDqI1XXsJz+ECZjPp2Bw==";

    @Test
    void signsExactlyAsOpensslDoesWithTheSameKey() throws GeneralSecurityException {
        Map<String, Object> envelope = envelope(ManifestSigner.sign("hello".getBytes(StandardCharsets.UTF_8), OPENSSL_KEY));

        assertEquals(OPENSSL_PUBLIC_KEY, envelope.get("publicKey"));
        assertEquals(OPENSSL_SIGNATURE_OF_HELLO, envelope.get("signature"));
        assertEquals(Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)), envelope.get("signedManifest"));
    }

    @Test
    void reportsTheFingerprintTheClientShows() throws GeneralSecurityException {
        ManifestSigner.Signed signed = ManifestSigner.sign("{}".getBytes(StandardCharsets.UTF_8), OPENSSL_KEY);

        // openssl pkey -in key.pem -pubout -outform DER | openssl dgst -sha256
        assertEquals("F7D8 2F72 D7F3 CED7", signed.getFingerprint());
    }

    @Test
    void producesAnEnvelopeTheClientVerifies() throws GeneralSecurityException {
        byte[] manifest = "{\"repository\":{}}".getBytes(StandardCharsets.UTF_8);
        ManifestSigner.GeneratedKey key = ManifestSigner.generate();

        Map<String, Object> envelope = envelope(ManifestSigner.sign(manifest, key.getPrivateKeyPem()));

        // The client's check: decode the manifest, then verify the signature over those bytes with the envelope's key.
        byte[] payload = Base64.getMimeDecoder().decode(String.valueOf(envelope.get("signedManifest")));
        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(String.valueOf(envelope.get("publicKey")))));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(payload);
        assertTrue(verifier.verify(Base64.getMimeDecoder().decode(String.valueOf(envelope.get("signature")))));
        assertArrayEquals(manifest, payload);
        assertEquals(key.getFingerprint(), ManifestSigner.fingerprint(publicKey.getEncoded()));
    }

    @Test
    void rejectsAKeyThatIsNotEd25519() {
        String rsaLooking = "-----BEGIN PRIVATE KEY-----\nMIIBVAIBADANBgkqhkiG9w0BAQEFAASCAT4wggE6AgEAAkEA\n-----END PRIVATE KEY-----\n";

        assertThrows(GeneralSecurityException.class, () -> ManifestSigner.sign(new byte[0], rsaLooking));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envelope(ManifestSigner.Signed signed) {
        return (Map<String, Object>) new JsonSlurper().parseText(new String(signed.getEnvelope(), StandardCharsets.UTF_8));
    }
}
