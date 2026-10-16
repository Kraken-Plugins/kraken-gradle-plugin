package com.krakenplugins.gradle.internal;

import groovy.json.JsonSlurper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryManifestTest {

    private static final RepositoryManifest.Metadata METADATA = new RepositoryManifest.Metadata("Kraken Example Plugins", "1.0.4",
            "cbartram", null, "https://github.com/Kraken-Plugins/kraken-example-plugin", "2026-10-15T12:00:00Z");

    @TempDir
    File directory;

    @Test
    void namesPluginsAfterTheirJarsLikeTheReleaseScriptDid() {
        assertEquals("Mining", RepositoryManifest.displayName("mining"));
        assertEquals("Auto Pot", RepositoryManifest.displayName("auto-pot"));
        assertEquals("Auto Pot", RepositoryManifest.displayName("AUTO-pot"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsEachJarWithItsUrlHashAndSize() throws IOException {
        File mining = jar("mining-1.0.0.jar", "mine");
        File autoPot = jar("auto-pot-2.1.0-beta.jar", "pots");

        Map<String, Object> manifest = parse(RepositoryManifest.write(METADATA, List.of(mining, autoPot),
                "https://github.com/o/r/releases/download/v1.0.4", Map.of()));

        Map<String, Object> repository = (Map<String, Object>) manifest.get("repository");
        assertEquals("Kraken Example Plugins", repository.get("name"));
        assertEquals("1.0.4", repository.get("version"));
        assertEquals("2026-10-15T12:00:00Z", repository.get("lastUpdated"));
        assertFalse(repository.containsKey("description"));

        List<Map<String, Object>> plugins = (List<Map<String, Object>>) manifest.get("plugins");
        assertEquals("Mining", plugins.get(0).get("name"));
        assertEquals("1.0.0", plugins.get(0).get("version"));
        Map<String, Object> artifact = (Map<String, Object>) plugins.get(0).get("artifact");
        assertEquals("https://github.com/o/r/releases/download/v1.0.4/mining-1.0.0.jar", artifact.get("url"));
        assertEquals(RuntimeApi.sha256("mine".getBytes(StandardCharsets.UTF_8)), artifact.get("sha256"));
        assertEquals(4, artifact.get("size"));
        assertEquals("Auto Pot", plugins.get(1).get("name"));
        assertEquals("2.1.0-beta", plugins.get(1).get("version"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsTheRepositoryIdStableAcrossReleases() throws IOException {
        File mining = jar("mining-1.0.0.jar", "mine");
        RepositoryManifest.Metadata later = new RepositoryManifest.Metadata("Kraken Example Plugins", "1.0.5", null, null, null, "x");

        Object first = ((Map<String, Object>) parse(RepositoryManifest.write(METADATA, List.of(mining), "u", Map.of())).get("repository")).get("id");
        Object second = ((Map<String, Object>) parse(RepositoryManifest.write(later, List.of(mining), "u", Map.of())).get("repository")).get("id");

        assertEquals(first, second);
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesConfiguredNames() throws IOException {
        File mining = jar("mining-1.0.0.jar", "mine");

        Map<String, Object> manifest = parse(RepositoryManifest.write(METADATA, List.of(mining), "u/", Map.of("mining", "Power Miner")));

        assertEquals("Power Miner", ((List<Map<String, Object>>) manifest.get("plugins")).get(0).get("name"));
    }

    @Test
    void rejectsJarsWithoutAVersionOrWithClashingNames() throws IOException {
        File unversioned = jar("mining.jar", "mine");
        IOException noVersion = assertThrows(IOException.class,
                () -> RepositoryManifest.write(METADATA, List.of(unversioned), "u", Map.of()));
        assertTrue(noVersion.getMessage().contains("has no version in its name"), noVersion.getMessage());

        File first = jar("auto-pot-1.0.0.jar", "a");
        File second = jar("Auto-Pot-2.0.0.jar", "b");
        IOException clash = assertThrows(IOException.class,
                () -> RepositoryManifest.write(METADATA, List.of(first, second), "u", Map.of()));
        assertTrue(clash.getMessage().contains("\"Auto Pot\""), clash.getMessage());
    }

    private File jar(String name, String content) throws IOException {
        File jar = new File(directory, name);
        Files.writeString(jar.toPath(), content);
        return jar;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(byte[] json) {
        return (Map<String, Object>) new JsonSlurper().parseText(new String(json, StandardCharsets.UTF_8));
    }
}
