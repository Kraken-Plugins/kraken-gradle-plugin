package com.krakenplugins.gradle.internal;

import groovy.json.JsonSlurper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Finds the Kraken API jar the Kraken client runs. Releases are checked against the production bootstrap, which is what
 * users' launchers download, so the published Kraken bootstrap is read first. Its API jar is verified against the
 * bootstrap's SHA-256 and kept in a cache directory. When the bootstrap can't be fetched, the launcher's saved copy is
 * used instead.
 *
 * <p>The bootstrap's signature is not checked: the jar is only read as class files, never loaded or run, so a hash that
 * proves the download is complete is all this needs.
 */
public final class RuntimeApi {

    public static final String PRODUCTION_BOOTSTRAP = "https://seaweed.kraken-plugins.com/kraken-bootstrap-static/bootstrap.json";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final File jar;
    private final ApiTarget target;
    private final String source;

    private RuntimeApi(File jar, String source) {
        this.jar = jar;
        this.target = ApiTarget.fromJar(jar);
        this.source = source;
    }

    /**
     * Finds the runtime API: from the bootstrap when online, falling back to the launcher's saved bootstrap when the
     * bootstrap can't be fetched or Gradle runs offline.
     *
     * @param bootstrapUrl    the bootstrap's URL
     * @param cacheDirectory  where verified API jars are kept between builds
     * @param krakenDirectory Kraken's data directory, holding the launcher's saved bootstrap
     * @param offline         whether Gradle runs with {@code --offline}
     * @param warn            receives a warning when the saved bootstrap is used because the bootstrap couldn't be fetched
     * @return the runtime API, or {@code null} when offline with no saved bootstrap
     * @throws IOException when the bootstrap can't be fetched and there is no saved bootstrap
     */
    public static RuntimeApi resolve(String bootstrapUrl, File cacheDirectory, File krakenDirectory, boolean offline,
                                     Consumer<String> warn) throws IOException {
        if (offline) {
            return fromSavedBootstrap(krakenDirectory);
        }
        try {
            return fromBootstrap(bootstrapUrl, cacheDirectory);
        } catch (IOException e) {
            RuntimeApi saved = fromSavedBootstrap(krakenDirectory);
            if (saved == null) {
                throw new IOException("Could not read the Kraken API the client runs from " + bootstrapUrl + " (" + e.getMessage()
                        + "), and there is no saved bootstrap in " + new File(krakenDirectory, KrakenBootstrap.REPOSITORY_DIRECTORY) + ".", e);
            }
            warn.accept("Could not read " + bootstrapUrl + " (" + e.getMessage() + "), so checking against the Kraken API in "
                    + saved.getSource() + " instead.");
            return saved;
        }
    }

    /**
     * Downloads, or reuses from the cache, the API jar a bootstrap lists.
     *
     * @param bootstrapUrl   the bootstrap's URL
     * @param cacheDirectory where verified API jars are kept between builds
     * @return the runtime API
     * @throws IOException when the bootstrap or jar can't be fetched, or the jar doesn't match the bootstrap's hash
     */
    public static RuntimeApi fromBootstrap(String bootstrapUrl, File cacheDirectory) throws IOException {
        String json = new String(read(new URL(bootstrapUrl)), StandardCharsets.UTF_8);
        Map<String, Object> artifact = apiArtifact(json, bootstrapUrl);
        String name = String.valueOf(artifact.get("name"));
        String path = String.valueOf(artifact.get("path"));
        String hash = String.valueOf(artifact.get("hash")).toLowerCase(Locale.ROOT);

        Files.createDirectories(cacheDirectory.toPath());
        File cached = new File(cacheDirectory, name);
        if (isCached(cached, hash)) {
            return new RuntimeApi(cached, bootstrapUrl);
        }

        byte[] bytes = read(new URL(path));
        String actual = sha256(bytes);
        if (!hash.equals(actual)) {
            throw new IOException(name + " from " + path + " has SHA-256 " + actual + ", but the bootstrap lists " + hash + ".");
        }
        File temporary = File.createTempFile(name, ".part", cacheDirectory);
        try {
            Files.write(temporary.toPath(), bytes);
            Files.move(temporary.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Another build may have just cached the same jar, and Windows refuses to replace a file that is open.
            if (!isCached(cached, hash)) {
                throw e;
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
        return new RuntimeApi(cached, bootstrapUrl);
    }

    private static boolean isCached(File cached, String hash) {
        try {
            return cached.isFile() && hash.equals(sha256(Files.readAllBytes(cached.toPath())));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Uses the API jar the launcher saved on its last online launch.
     *
     * @param krakenDirectory Kraken's data directory, normally {@code ~/.runelite/kraken}
     * @return the runtime API, or {@code null} when there is no saved bootstrap or its API jar isn't cached
     */
    public static RuntimeApi fromSavedBootstrap(File krakenDirectory) {
        File jar = KrakenBootstrap.runtimeApiJar(krakenDirectory);
        return jar == null ? null : new RuntimeApi(jar, "the Kraken launcher's saved bootstrap");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> apiArtifact(String json, String bootstrapUrl) throws IOException {
        Object parsed;
        try {
            parsed = new JsonSlurper().parseText(json);
        } catch (RuntimeException e) {
            throw new IOException(bootstrapUrl + " is not valid JSON: " + e.getMessage(), e);
        }
        Object artifacts = parsed instanceof Map ? ((Map<String, Object>) parsed).get("artifacts") : null;
        if (artifacts instanceof List) {
            for (Object artifact : (List<Object>) artifacts) {
                if (artifact instanceof Map && ApiTarget.versionFromFileName(String.valueOf(((Map<String, Object>) artifact).get("name"))) != null) {
                    return (Map<String, Object>) artifact;
                }
            }
        }
        throw new IOException(bootstrapUrl + " lists no Kraken API jar.");
    }

    private static byte[] read(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        try (InputStream in = connection.getInputStream()) {
            return in.readAllBytes();
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public File getJar() {
        return jar;
    }

    public ApiTarget getTarget() {
        return target;
    }

    /**
     * @return where the jar came from, for log output
     */
    public String getSource() {
        return source;
    }
}
