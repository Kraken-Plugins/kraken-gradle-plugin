package com.krakenplugins.gradle.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the JVM command the Kraken launcher's installer configures RuneLite with: RuneLite's own launcher, with the
 * Kraken launcher attached as a Java agent. The agent downloads the signed Kraken bootstrap (or uses the saved copy when
 * offline), so the client runs the Kraken API and Kraken-Client that match the current RuneLite, whatever the project
 * compiles against. {@code kraken.sideload.paths} tells Kraken-Client to load the given plugin jars as sideloaded plugins.
 */
public final class LaunchCommand {

    public static final String MAIN_CLASS = "net.runelite.launcher.Launcher";
    public static final String RUNELITE_JAR = "RuneLite.jar";
    public static final String KRAKEN_AGENT_JAR = "KrakenSetup.jar";
    static final String SIDELOAD_PATHS_PROPERTY = "kraken.sideload.paths";
    static final int DEBUG_PORT = 5005;

    /** The same module access the installer adds to RuneLite's config.json. */
    private static final List<String> REQUIRED_VM_ARGS = Arrays.asList(
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-exports=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-exports=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-exports=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.reflect=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED"
    );

    private static final List<String> MAC_REQUIRED_VM_ARGS = Arrays.asList(
            "--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED",
            "--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED"
    );

    private LaunchCommand() {
    }

    /**
     * Builds the JVM arguments.
     *
     * @param runeliteDirectory the RuneLite installation directory holding {@code KrakenSetup.jar}
     * @param sideloadPaths     plugin jars and directories of jars to sideload
     * @param debug             whether to wait for a debugger on {@link #DEBUG_PORT}
     * @param mac               whether the client runs on macOS
     * @return the JVM arguments, in order
     */
    public static List<String> jvmArguments(File runeliteDirectory, List<File> sideloadPaths, boolean debug, boolean mac) {
        List<String> arguments = new ArrayList<>();
        arguments.add("-javaagent:" + new File(runeliteDirectory, KRAKEN_AGENT_JAR).getAbsolutePath());
        arguments.addAll(REQUIRED_VM_ARGS);
        if (mac) {
            arguments.addAll(MAC_REQUIRED_VM_ARGS);
        }
        // RuneLite's --developer-mode requires assertions.
        arguments.add("-ea");
        arguments.add("-Drunelite.launcher.nojvm=true");
        arguments.add("-Xmx768m");
        arguments.add("-Xss2m");
        arguments.add("-D" + SIDELOAD_PATHS_PROPERTY + "=" + sideloadPaths.stream()
                .map(File::getAbsolutePath)
                .collect(Collectors.joining(File.pathSeparator)));
        if (debug) {
            arguments.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:" + DEBUG_PORT);
        }
        return arguments;
    }

    /**
     * Builds the arguments passed to RuneLite's launcher.
     *
     * @param clientArguments RuneLite arguments such as {@code --developer-mode}
     * @param profile         the linked Jagex profile to log in as, or {@code null} for none
     * @return the program arguments, in order
     */
    public static List<String> programArguments(List<String> clientArguments, String profile) {
        List<String> arguments = new ArrayList<>(clientArguments);
        if (profile != null && !profile.isBlank()) {
            arguments.add("--kraken-profile");
            arguments.add(profile);
        }
        return arguments;
    }
}
