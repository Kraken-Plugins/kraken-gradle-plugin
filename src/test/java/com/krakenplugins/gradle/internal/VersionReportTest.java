package com.krakenplugins.gradle.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionReportTest {

    @TempDir
    File temp;

    @Test
    void comparesDottedVersionsNumerically() {
        assertTrue(Versions.compare("5.1.10", "5.1.9") > 0);
        assertTrue(Versions.compare("5.1", "5.1.1") < 0);
        assertEquals(0, Versions.compare("5.1.6-test", "5.1.6"));
    }

    @Test
    void readsTheTargetFromTheManifestAndFallsBackToTheFileName() throws Exception {
        ApiTarget withManifest = ApiTarget.fromJar(TestJars.krakenApi(new File(temp, "kraken-api-5.1.6.jar"), "5.1.6", "1.13.1", "241"));
        assertEquals("5.1.6 (hooks for RuneLite 1.13.1, revision 241)", withManifest.describe());

        ApiTarget legacy = ApiTarget.fromJar(TestJars.jarWithManifest(new File(temp, "kraken-api-5.1.4.jar"), Map.of()));
        assertEquals("5.1.4", legacy.getApiVersion());
        assertNull(legacy.getRuneliteVersion());
    }

    @Test
    void readsTheRuntimeApiFromTheSavedBootstrap() throws Exception {
        File kraken = new File(temp, "kraken");
        TestJars.bootstrap(kraken, "kraken-api-5.1.5.jar");
        assertEquals("5.1.5", KrakenBootstrap.runtimeApi(kraken).getApiVersion());

        TestJars.krakenApi(new File(kraken, "repository2/kraken-api-5.1.5.jar"), "5.1.5", "1.13.1", "241");
        assertEquals("1.13.1", KrakenBootstrap.runtimeApi(kraken).getRuneliteVersion());

        assertNull(KrakenBootstrap.runtimeApi(new File(temp, "missing")));
    }

    @Test
    void warnsWhenCompilingAgainstANewerApiThanTheClientRuns() {
        VersionReport report = VersionReport.create(new ApiTarget("5.1.7", "1.13.1", "241"), "1.13.1",
                new ApiTarget("5.1.6", "1.13.1", "241"), temp);

        assertEquals(1, report.getWarnings().size());
        assertTrue(report.getWarnings().get(0).contains("NoSuchMethodError"), report.getWarnings().toString());
    }

    @Test
    void acceptsAnOlderApiThanTheClientRuns() {
        VersionReport report = VersionReport.create(new ApiTarget("5.1.3", "1.13.1", "241"), "1.13.1",
                new ApiTarget("5.1.6", "1.13.1", "241"), temp);

        assertEquals(List.of(), report.getWarnings());
    }

    @Test
    void warnsWhenTheApiHooksTargetADifferentRuneLite() {
        VersionReport report = VersionReport.create(new ApiTarget("5.1.3", "1.12.39", "240"), "1.13.1",
                new ApiTarget("5.1.6", "1.13.1", "241"), temp);

        assertEquals(1, report.getWarnings().size());
        assertTrue(report.getWarnings().get(0).contains("hooks for RuneLite 1.12.39"), report.getWarnings().toString());
    }

    @Test
    void explainsHowToCreateTheBootstrapWhenItIsMissing() {
        VersionReport report = VersionReport.create(new ApiTarget("5.1.6", null, null), null, null, temp);

        assertEquals(List.of(), report.getWarnings());
        assertTrue(report.getLines().get(1).contains("Launch the Kraken client once while online"), report.getLines().toString());
    }

    @Test
    void recognisesTheApiAndRuneLiteOnTheCompileClasspath() throws Exception {
        File api = TestJars.krakenApi(new File(temp, "api.jar"), "5.1.6", "1.13.1", "241");
        File fileDependency = TestJars.krakenApi(new File(temp, "kraken-api-5.1.6.jar"), "5.1.6", "1.13.1", "241");

        assertTrue(CompileLibrary.parse(CompileLibrary.describe("com.github.kraken", "kraken-api", "5.1.6", api)).isKrakenApi());
        assertTrue(CompileLibrary.parse(CompileLibrary.describe(null, null, null, fileDependency)).isKrakenApi());
        CompileLibrary client = CompileLibrary.parse(CompileLibrary.describe("net.runelite", "client", "1.13.1", new File(temp, "client.jar")));
        assertTrue(client.isRuneLiteClient());
        assertEquals("1.13.1", client.getVersion());
    }
}
