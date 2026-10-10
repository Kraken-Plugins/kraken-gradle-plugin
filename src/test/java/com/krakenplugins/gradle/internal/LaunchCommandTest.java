package com.krakenplugins.gradle.internal;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchCommandTest {

    private final File runelite = new File("RuneLite");

    @Test
    void attachesTheKrakenAgentAndPassesTheSideloadedJars() {
        File first = new File("build/libs/mining-1.0.jar");
        File second = new File("other/build/libs");

        List<String> arguments = LaunchCommand.jvmArguments(runelite, List.of(first, second), false, false);

        assertEquals("-javaagent:" + new File(runelite, "KrakenSetup.jar").getAbsolutePath(), arguments.get(0));
        assertTrue(arguments.contains("-Drunelite.launcher.nojvm=true"));
        assertTrue(arguments.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"));
        assertTrue(arguments.contains("-Dkraken.sideload.paths=" + first.getAbsolutePath() + File.pathSeparator + second.getAbsolutePath()));
        assertFalse(arguments.stream().anyMatch(argument -> argument.startsWith("-agentlib:jdwp")));
        assertFalse(arguments.stream().anyMatch(argument -> argument.contains("com.apple.eawt")));
    }

    @Test
    void addsTheDebuggerAndMacArgumentsWhenAsked() {
        List<String> arguments = LaunchCommand.jvmArguments(runelite, List.of(), true, true);

        assertTrue(arguments.contains("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"));
        assertTrue(arguments.contains("--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED"));
    }

    @Test
    void appendsTheProfileToTheClientArguments() {
        assertEquals(List.of("--developer-mode", "--kraken-profile", "MyCharacter"),
                LaunchCommand.programArguments(List.of("--developer-mode"), "MyCharacter"));
        assertEquals(List.of("--developer-mode"), LaunchCommand.programArguments(List.of("--developer-mode"), " "));
    }
}
