package com.krakenplugins.gradle.internal;

import java.io.File;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Kraken API jar's version and the RuneLite version and game revision its hooks were mapped for, read from the
 * {@code Kraken-Api-Version}, {@code Kraken-RuneLite-Version} and {@code Kraken-Game-Revision} manifest attributes.
 * Jars built before those attributes existed only yield the version from their file name.
 */
public final class ApiTarget {

    static final String API_VERSION_ATTRIBUTE = "Kraken-Api-Version";
    static final String RUNELITE_VERSION_ATTRIBUTE = "Kraken-RuneLite-Version";
    static final String REVISION_ATTRIBUTE = "Kraken-Game-Revision";
    private static final Pattern API_JAR_NAME = Pattern.compile("kraken-api-(\\d[^/\\\\]*?)\\.jar");

    private final String apiVersion;
    private final String runeliteVersion;
    private final String revision;

    ApiTarget(String apiVersion, String runeliteVersion, String revision) {
        this.apiVersion = apiVersion;
        this.runeliteVersion = runeliteVersion;
        this.revision = revision;
    }

    /**
     * Reads the target of a Kraken API jar.
     *
     * @param jar the Kraken API jar
     * @return the target; its fields are {@code null} where neither the manifest nor the file name gives them
     */
    public static ApiTarget fromJar(File jar) {
        String apiVersion = null;
        String runeliteVersion = null;
        String revision = null;
        try (JarFile jarFile = new JarFile(jar)) {
            Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                Attributes attributes = manifest.getMainAttributes();
                apiVersion = attributes.getValue(API_VERSION_ATTRIBUTE);
                runeliteVersion = attributes.getValue(RUNELITE_VERSION_ATTRIBUTE);
                revision = attributes.getValue(REVISION_ATTRIBUTE);
            }
        } catch (IOException ignored) {
            // An unreadable jar still has a file name to take the version from.
        }
        if (apiVersion == null) {
            apiVersion = versionFromFileName(jar.getName());
        }
        return new ApiTarget(apiVersion, runeliteVersion, revision);
    }

    /**
     * Extracts the version from a Kraken API jar name such as {@code kraken-api-5.1.6.jar}.
     *
     * @param fileName the jar's file name
     * @return the version, or {@code null} when the name doesn't follow that pattern
     */
    static String versionFromFileName(String fileName) {
        Matcher matcher = API_JAR_NAME.matcher(fileName);
        return matcher.matches() ? matcher.group(1) : null;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public String getRuneliteVersion() {
        return runeliteVersion;
    }

    public String getRevision() {
        return revision;
    }

    /**
     * Describes the jar for log output, e.g. {@code 5.1.6 (hooks for RuneLite 1.13.1, revision 241)}.
     *
     * @return the description
     */
    public String describe() {
        String version = apiVersion == null ? "unknown version" : apiVersion;
        if (runeliteVersion == null) {
            return version;
        }
        return version + " (hooks for RuneLite " + runeliteVersion + (revision == null ? "" : ", revision " + revision) + ")";
    }
}
