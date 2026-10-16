package com.krakenplugins.gradle.internal;

import groovy.json.JsonOutput;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the external repository manifest the Kraken client reads: the repository's metadata and, for each plugin jar,
 * its name, version, download URL, SHA-256 and size.
 *
 * <p>The client remembers which plugins a user approved by their manifest name, so names must stay the same from one
 * release to the next. A plugin is named after its jar ({@code auto-mining-1.2.0.jar} becomes {@code Auto Mining},
 * version {@code 1.2.0}) unless the build gives it another name.
 */
public final class RepositoryManifest {

    private static final Pattern JAR_NAME = Pattern.compile("(.+?)-(\\d[^/\\\\]*)\\.jar");

    private RepositoryManifest() {
    }

    /**
     * Repository-level metadata.
     */
    public static final class Metadata {

        final String name;
        final String version;
        final String author;
        final String description;
        final String website;
        final String lastUpdated;

        /**
         * @param name        the repository name users see
         * @param version     the release version
         * @param author      the author, or {@code null}
         * @param description a description, or {@code null}
         * @param website     the repository's website, or {@code null}
         * @param lastUpdated when the release was made, in ISO-8601
         */
        public Metadata(String name, String version, String author, String description, String website, String lastUpdated) {
            this.name = name;
            this.version = version;
            this.author = author;
            this.description = description;
            this.website = website;
            this.lastUpdated = lastUpdated;
        }
    }

    /**
     * Writes the manifest JSON.
     *
     * @param metadata        the repository's metadata
     * @param jars            the plugin jars, in manifest order
     * @param artifactBaseUrl the URL the jars are downloaded from, which each jar's file name is appended to
     * @param names           manifest names to use instead of the one derived from a jar, keyed by the jar's base name
     *                        (its file name without the version and {@code .jar})
     * @return the manifest bytes
     * @throws IOException when a jar can't be read or its name carries no version
     */
    public static byte[] write(Metadata metadata, List<File> jars, String artifactBaseUrl, Map<String, String> names) throws IOException {
        Map<String, Object> repository = new LinkedHashMap<>();
        // The client keys repositories on their URL, not this id; a stable id just keeps releases of one repository alike.
        repository.put("id", UUID.nameUUIDFromBytes(("kraken-repository:" + metadata.name).getBytes(StandardCharsets.UTF_8)).toString());
        repository.put("name", metadata.name);
        repository.put("version", metadata.version);
        putIfPresent(repository, "author", metadata.author);
        putIfPresent(repository, "description", metadata.description);
        putIfPresent(repository, "website", metadata.website);
        repository.put("lastUpdated", metadata.lastUpdated);

        String baseUrl = artifactBaseUrl.endsWith("/") ? artifactBaseUrl : artifactBaseUrl + "/";
        List<Object> plugins = new ArrayList<>();
        List<String> seenNames = new ArrayList<>();
        for (File jar : jars) {
            Matcher matcher = JAR_NAME.matcher(jar.getName());
            if (!matcher.matches()) {
                throw new IOException(jar.getName() + " has no version in its name. Set version in the plugin's build.gradle so"
                        + " the jar is named <name>-<version>.jar.");
            }
            String baseName = matcher.group(1);
            String name = names.getOrDefault(baseName, displayName(baseName));
            if (seenNames.contains(name)) {
                throw new IOException("Two jars would both be listed as the plugin \"" + name + "\". Give one of them another"
                        + " name with kraken.release.pluginNames.");
            }
            seenNames.add(name);

            byte[] bytes = Files.readAllBytes(jar.toPath());
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("url", baseUrl + jar.getName());
            artifact.put("sha256", RuntimeApi.sha256(bytes));
            artifact.put("size", bytes.length);

            Map<String, Object> plugin = new LinkedHashMap<>();
            plugin.put("name", name);
            plugin.put("version", matcher.group(2));
            plugin.put("artifact", artifact);
            plugins.add(plugin);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("repository", repository);
        manifest.put("plugins", plugins);
        return (JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Turns a jar's base name into the name users see, e.g. {@code auto-mining} into {@code Auto Mining}.
     *
     * @param baseName the jar's file name without its version and extension
     * @return the display name
     */
    static String displayName(String baseName) {
        StringBuilder out = new StringBuilder();
        for (String word : baseName.split("[-\\s]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }
}
