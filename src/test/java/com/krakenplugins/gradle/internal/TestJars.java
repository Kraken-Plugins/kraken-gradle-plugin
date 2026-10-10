package com.krakenplugins.gradle.internal;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Writes empty jars with chosen manifest attributes, standing in for Kraken API jars.
 */
public final class TestJars {

    private TestJars() {
    }

    /**
     * Writes an empty jar whose manifest carries the given attributes.
     *
     * @param jar        the jar to write
     * @param attributes manifest attributes
     * @return the jar
     * @throws IOException when the jar can't be written
     */
    public static File jarWithManifest(File jar, Map<String, String> attributes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.forEach((name, value) -> manifest.getMainAttributes().putValue(name, value));
        Files.createDirectories(jar.getParentFile().toPath());
        try (OutputStream out = Files.newOutputStream(jar.toPath()); JarOutputStream ignored = new JarOutputStream(out, manifest)) {
            return jar;
        }
    }

    /**
     * Writes a Kraken API jar with hooks for the given RuneLite version and revision.
     *
     * @param jar             the jar to write
     * @param apiVersion      the API version
     * @param runeliteVersion the RuneLite version its hooks target
     * @param revision        the game revision its hooks target
     * @return the jar
     * @throws IOException when the jar can't be written
     */
    public static File krakenApi(File jar, String apiVersion, String runeliteVersion, String revision) throws IOException {
        return jarWithManifest(jar, Map.of(
                ApiTarget.API_VERSION_ATTRIBUTE, apiVersion,
                ApiTarget.RUNELITE_VERSION_ATTRIBUTE, runeliteVersion,
                ApiTarget.REVISION_ATTRIBUTE, revision));
    }

    /**
     * Writes a saved Kraken bootstrap listing the given API jar.
     *
     * @param krakenDirectory Kraken's data directory
     * @param apiJarName      the API jar's file name
     * @throws IOException when the bootstrap can't be written
     */
    public static void bootstrap(File krakenDirectory, String apiJarName) throws IOException {
        File repository = new File(krakenDirectory, KrakenBootstrap.REPOSITORY_DIRECTORY);
        Files.createDirectories(repository.toPath());
        String json = "{\n  \"artifacts\" : [ {\n    \"name\" : \"Kraken-Client-1.18.0.jar\"\n  }, {\n"
                + "    \"name\" : \"" + apiJarName + "\",\n    \"size\" : 1\n  } ]\n}";
        Files.writeString(new File(repository, KrakenBootstrap.BOOTSTRAP_FILE).toPath(), json);
    }
}
