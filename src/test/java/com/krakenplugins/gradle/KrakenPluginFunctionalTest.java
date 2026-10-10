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

    private GradleRunner runner(String task) {
        return GradleRunner.create()
                .withProjectDir(projectDirectory)
                .withPluginClasspath()
                .withArguments(task, "--configuration-cache", "--stacktrace");
    }

    private void write(String path, String content) throws IOException {
        File file = new File(projectDirectory, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }
}
