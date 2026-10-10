package com.krakenplugins.gradle;

import com.krakenplugins.gradle.internal.TestJars;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the plugin to a real build. The build compiles against a stand-in Kraken API jar and reads a saved
 * bootstrap from a temporary Kraken directory, so nothing touches the real installation or launches the client.
 */
class KrakenPluginFunctionalTest {

    @TempDir
    File projectDirectory;

    @BeforeEach
    void writeProject() throws IOException {
        TestJars.krakenApi(new File(projectDirectory, "libs/kraken-api-5.1.7.jar"), "5.1.7", "1.13.1", "241");
        File kraken = new File(projectDirectory, "kraken-home");
        TestJars.bootstrap(kraken, "kraken-api-5.1.6.jar");
        TestJars.krakenApi(new File(kraken, "repository2/kraken-api-5.1.6.jar"), "5.1.6", "1.13.1", "241");

        write("settings.gradle", "rootProject.name = 'my-plugin'\n");
        write("build.gradle", String.join("\n",
                "plugins { id 'com.krakenplugins.plugin' }",
                "dependencies { compileOnly files('libs/kraken-api-5.1.7.jar') }",
                "kraken {",
                "    krakenDirectory = file('kraken-home')",
                "    runeliteDirectory = file('no-runelite-here')",
                "}",
                ""));
        write("src/main/java/com/krakenplugins/example/Example.java",
                "package com.krakenplugins.example;\npublic class Example {}\n");
    }

    @Test
    void reportsTheCompileAndRuntimeApiVersions() {
        BuildResult result = runner("krakenVersions").build();

        assertTrue(result.getOutput().contains("Compiling against Kraken API 5.1.7 (hooks for RuneLite 1.13.1, revision 241)"), result.getOutput());
        assertTrue(result.getOutput().contains("The Kraken client runs Kraken API 5.1.6"), result.getOutput());
        assertTrue(result.getOutput().contains("NoSuchMethodError"), result.getOutput());
    }

    @Test
    void runKrakenBuildsTheJarAndExplainsAMissingInstallation() {
        BuildResult result = runner("runKraken").buildAndFail();

        assertTrue(result.getOutput().contains("Task :jar"), result.getOutput());
        assertTrue(result.getOutput().contains("RuneLite.jar was not found in"), result.getOutput());
        assertTrue(result.getOutput().contains("Install the Kraken launcher"), result.getOutput());
    }

    @Test
    void runKrakenFromTheRootLaunchesTheClientOnce() throws IOException {
        write("settings.gradle", "rootProject.name = 'my-plugins'\ninclude 'mining', 'fishing'\n");
        write("build.gradle", String.join("\n",
                "plugins { id 'com.krakenplugins.plugin' }",
                "allprojects {",
                "    apply plugin: 'com.krakenplugins.plugin'",
                "    kraken {",
                "        krakenDirectory = rootProject.file('kraken-home')",
                "        runeliteDirectory = rootProject.file('no-runelite-here')",
                "    }",
                "}",
                "kraken { sideload.setFrom(subprojects.collect { it.tasks.named('jar') }) }",
                ""));

        for (int run = 0; run < 2; run++) {
            String output = runner("runKraken", "--continue").buildAndFail().getOutput();

            assertTrue(output.contains("Task :mining:jar") && output.contains("Task :fishing:jar"), output);
            assertEquals(2, output.split("Skipping: :runKraken launches the client.", -1).length - 1, output);
            assertTrue(output.contains("Task :runKraken FAILED"), output);
            assertFalse(output.contains("Task :mining:runKraken FAILED") || output.contains("Task :fishing:runKraken FAILED"), output);
        }
    }

    @Test
    void runKrakenInASubprojectStillLaunches() throws IOException {
        write("settings.gradle", "rootProject.name = 'my-plugins'\ninclude 'mining'\n");
        write("build.gradle", String.join("\n",
                "plugins { id 'com.krakenplugins.plugin' }",
                "allprojects {",
                "    apply plugin: 'com.krakenplugins.plugin'",
                "    kraken {",
                "        krakenDirectory = rootProject.file('kraken-home')",
                "        runeliteDirectory = rootProject.file('no-runelite-here')",
                "    }",
                "}",
                ""));

        String output = runner(":mining:runKraken").buildAndFail().getOutput();

        assertTrue(output.contains("RuneLite.jar was not found in"), output);
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
}
