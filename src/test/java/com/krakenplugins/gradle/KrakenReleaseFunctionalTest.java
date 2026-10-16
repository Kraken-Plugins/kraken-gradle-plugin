package com.krakenplugins.gradle;

import com.krakenplugins.gradle.internal.TestCompiler;
import com.krakenplugins.gradle.internal.TestJars;
import groovy.json.JsonSlurper;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs {@code krakenCheck}, {@code krakenManifest}, {@code krakenRelease} and the dependency wiring in real builds. The
 * Kraken bootstrap is a local file naming a compiled stand-in Kraken API, so nothing touches the network.
 */
class KrakenReleaseFunctionalTest {

    private static final String SIGNING_KEY = "-----BEGIN PRIVATE KEY-----\n"
            + "MC4CAQAwBQYDK2VwBCIEIOjIK/mrWzM1u3vRtrncWNuLE3rKdJy2g98r79OO2Z4k\n"
            + "-----END PRIVATE KEY-----\n";

    @TempDir
    File projectDirectory;

    @BeforeEach
    void writeApis() throws Exception {
        TestCompiler.jar(new File(projectDirectory, "libs/descriptor.jar"), 11, Map.of(
                "net/runelite/client/plugins/PluginDescriptor.java", "package net.runelite.client.plugins;\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface PluginDescriptor { String name(); }\n"));
        File runtimeApi = TestCompiler.jar(new File(projectDirectory, "server/kraken-api-5.2.1.jar"), 11, Map.of(
                "com/kraken/api/Context.java", "package com.kraken.api;\npublic class Context { public void players() {} }\n"));
        TestCompiler.jar(new File(projectDirectory, "libs/kraken-api-5.2.2.jar"), 11, Map.of(
                "com/kraken/api/Context.java", "package com.kraken.api;\n"
                        + "public class Context { public void players() {} public void npcs() {} }\n"));
        writeBootstrap(runtimeApi, sha256(runtimeApi));
        write("settings.gradle", "rootProject.name = 'my-plugins'\ninclude 'mining', 'fishing'\n");
        write("build.gradle", String.join("\n",
                "plugins { id 'com.krakenplugins.plugin' }",
                "allprojects {",
                "    apply plugin: 'com.krakenplugins.plugin'",
                "    dependencies { compileOnly rootProject.files('libs/kraken-api-5.2.2.jar', 'libs/descriptor.jar') }",
                "    kraken {",
                "        addRepositories = false",
                "        bootstrapUrl = rootProject.file('server/bootstrap.json').toURI().toString()",
                "        krakenDirectory = rootProject.file('kraken-home')",
                "    }",
                "}",
                "subprojects { version = '1.2.0' }",
                "version = '1.0.4'",
                "kraken.release {",
                "    name = 'My Plugins'",
                "    author = 'me'",
                "    github = 'me/my-plugins'",
                "}",
                ""));
        plugin("mining", "ctx.players();");
        plugin("fishing", "ctx.players();");
    }

    @Test
    void checkPassesForPluginsUsingOnlyTheRuntimeApiAndSkipsTheEmptyRoot() {
        BuildResult result = runner("check").build();

        assertEquals(TaskOutcome.NO_SOURCE, result.task(":krakenCheck").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":mining:krakenCheck").getOutcome());
        assertTrue(result.getOutput().contains("Checked 2 Kraken API references in mining-1.2.0.jar against Kraken API 5.2.1"),
                result.getOutput());
    }

    @Test
    void checkFailsWhenAPluginCallsWhatTheClientLacks() throws IOException {
        plugin("fishing", "ctx.npcs();");

        BuildResult result = runner(":fishing:check").buildAndFail();

        assertTrue(result.getOutput().contains("fishing-1.2.0.jar won't work in the Kraken client, which runs Kraken API 5.2.1"),
                result.getOutput());
        assertTrue(result.getOutput().contains("com.example.fishing.Plugin calls com.kraken.api.Context.npcs(), which Kraken API 5.2.1 doesn't have."),
                result.getOutput());
        assertTrue(result.getOutput().contains("The build compiles against Kraken API 5.2.2, which is newer."), result.getOutput());
    }

    @Test
    void checkFallsBackToTheSavedBootstrapWhenTheJarDoesNotMatch() throws IOException {
        writeBootstrap(new File(projectDirectory, "server/kraken-api-5.2.1.jar"), "00");
        File home = new File(projectDirectory, "kraken-home");
        TestJars.bootstrap(home, "kraken-api-5.2.1.jar");
        Files.copy(new File(projectDirectory, "server/kraken-api-5.2.1.jar").toPath(), new File(home, "repository2/kraken-api-5.2.1.jar").toPath());

        BuildResult result = runner(":mining:krakenCheck").build();

        assertTrue(result.getOutput().contains("but the bootstrap lists 00"), result.getOutput());
        assertTrue(result.getOutput().contains("from the Kraken launcher's saved bootstrap"), result.getOutput());
    }

    @Test
    void checkExplainsWhenThereIsNothingToCheckAgainst() throws IOException {
        writeBootstrap(new File(projectDirectory, "server/kraken-api-5.2.1.jar"), "00");

        BuildResult result = runner(":mining:krakenCheck").buildAndFail();

        assertTrue(result.getOutput().contains("Could not read the Kraken API the client runs from"), result.getOutput());
        assertTrue(result.getOutput().contains("there is no saved bootstrap in"), result.getOutput());
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseChecksAndSignsEveryPluginOfTheBuild() throws Exception {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put(KrakenReleaseTask.SIGNING_KEY_VARIABLE, SIGNING_KEY);

        BuildResult result = runner("krakenRelease").withEnvironment(environment).build();

        assertTrue(result.getOutput().contains("Checked 4 Kraken API references in 2 plugin jars"), result.getOutput());
        assertTrue(result.getOutput().contains("signed with key F7D8 2F72 D7F3 CED7"), result.getOutput());
        assertNull(result.task(":mining:krakenRelease"));

        File release = new File(projectDirectory, "build/kraken-release");
        assertTrue(new File(release, "mining-1.2.0.jar").isFile());
        assertTrue(new File(release, "fishing-1.2.0.jar").isFile());
        assertEquals("F7D8 2F72 D7F3 CED7", Files.readString(new File(projectDirectory, "build/kraken/signing-key-fingerprint.txt").toPath()).trim());

        Map<String, Object> envelope = (Map<String, Object>) new JsonSlurper().parse(new File(release, "manifest.json"));
        byte[] payload = Base64.getMimeDecoder().decode(String.valueOf(envelope.get("signedManifest")));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getMimeDecoder().decode(String.valueOf(envelope.get("publicKey"))))));
        verifier.update(payload);
        assertTrue(verifier.verify(Base64.getMimeDecoder().decode(String.valueOf(envelope.get("signature")))));

        Map<String, Object> manifest = (Map<String, Object>) new JsonSlurper().parseText(new String(payload, StandardCharsets.UTF_8));
        Map<String, Object> repository = (Map<String, Object>) manifest.get("repository");
        assertEquals("My Plugins", repository.get("name"));
        assertEquals("1.0.4", repository.get("version"));
        assertEquals("https://github.com/me/my-plugins", repository.get("website"));
        List<Map<String, Object>> plugins = (List<Map<String, Object>>) manifest.get("plugins");
        assertEquals("Fishing", plugins.get(0).get("name"));
        assertEquals("1.2.0", plugins.get(0).get("version"));
        assertEquals("https://github.com/me/my-plugins/releases/download/v1.0.4/fishing-1.2.0.jar",
                ((Map<String, Object>) plugins.get(0).get("artifact")).get("url"));
        assertEquals("Mining", plugins.get(1).get("name"));
    }

    @Test
    void releaseRefusesToPublishAnUnsignedManifest() {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.remove(KrakenReleaseTask.SIGNING_KEY_VARIABLE);
        environment.remove(KrakenReleaseTask.SIGNING_KEY_FILE_VARIABLE);

        BuildResult result = runner("krakenRelease").withEnvironment(environment).buildAndFail();

        assertTrue(result.getOutput().contains("The manifest must be signed"), result.getOutput());
        assertFalse(new File(projectDirectory, "build/kraken-release/manifest.json").exists());
    }

    @Test
    void manifestExplainsAMissingDownloadLocation() throws IOException {
        write("build.gradle", Files.readString(new File(projectDirectory, "build.gradle").toPath())
                .replace("    github = 'me/my-plugins'\n", ""));

        BuildResult result = runner("krakenManifest").buildAndFail();

        assertTrue(result.getOutput().contains("Set kraken.release.github = 'owner/repository'"), result.getOutput());
    }

    @Test
    void keygenWritesAKeyAndNeverReplacesOne() {
        File key = new File(projectDirectory, "keys/signing.pem");

        BuildResult created = runner("krakenKeygen", "--output", key.getAbsolutePath()).build();
        BuildResult refused = runner("krakenKeygen", "--output", key.getAbsolutePath()).buildAndFail();

        assertTrue(created.getOutput().contains("Fingerprint: "), created.getOutput());
        assertTrue(key.isFile());
        assertTrue(refused.getOutput().contains("already exists"), refused.getOutput());
    }

    @Test
    void addsTheDependenciesAndRepositoriesItIsGivenVersionsFor() throws IOException {
        write("settings.gradle", "rootProject.name = 'one-plugin'\n");
        write("build.gradle", String.join("\n",
                "plugins { id 'com.krakenplugins.plugin' }",
                "kraken {",
                "    apiVersion = '5.2.1'",
                "    runeliteVersion = '1.13.1'",
                "    lombokVersion = '1.18.30'",
                "}",
                "gradle.projectsEvaluated {",
                "    ['compileOnly', 'annotationProcessor', 'testImplementation', 'testAnnotationProcessor'].each { name ->",
                "        println name + ': ' + configurations[name].dependencies.collect { \"${it.group}:${it.name}:${it.version}\" }.sort()",
                "    }",
                "    println 'repositories: ' + repositories.collect { it.name }",
                "}",
                ""));

        String output = runner("help").build().getOutput();

        assertTrue(output.contains("compileOnly: [com.github.kraken:kraken-api:5.2.1, net.runelite:client:1.13.1, org.projectlombok:lombok:1.18.30]"), output);
        assertTrue(output.contains("annotationProcessor: [org.projectlombok:lombok:1.18.30]"), output);
        assertTrue(output.contains("testImplementation: [com.github.kraken:kraken-api:5.2.1, net.runelite:client:1.13.1]"), output);
        assertTrue(output.contains("testAnnotationProcessor: [org.projectlombok:lombok:1.18.30]"), output);
        assertTrue(output.contains("repositories: [Kraken, RuneLite, MavenRepo]"), output);
    }

    private void plugin(String name, String body) throws IOException {
        write(name + "/src/main/java/com/example/" + name + "/Plugin.java", "package com.example." + name + ";\n"
                + "@net.runelite.client.plugins.PluginDescriptor(name = \"" + name + "\")\n"
                + "public class Plugin { void run(com.kraken.api.Context ctx) { " + body + " } }\n");
    }

    private void writeBootstrap(File apiJar, String hash) throws IOException {
        write("server/bootstrap.json", "{\"artifacts\": [{\"name\": \"Kraken-Client-1.19.0.jar\", \"path\": \"x\", \"hash\": \"00\"},"
                + " {\"name\": \"" + apiJar.getName() + "\", \"path\": \"" + apiJar.toURI() + "\", \"hash\": \"" + hash + "\"}]}");
    }

    private GradleRunner runner(String... tasks) {
        List<String> arguments = new ArrayList<>(List.of(tasks));
        arguments.addAll(List.of("--configuration-cache", "--stacktrace"));
        return GradleRunner.create()
                .withProjectDir(projectDirectory)
                .withPluginClasspath()
                .withArguments(arguments);
    }

    private void write(String path, String content) throws IOException {
        File file = new File(projectDirectory, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }

    private static String sha256(File file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
