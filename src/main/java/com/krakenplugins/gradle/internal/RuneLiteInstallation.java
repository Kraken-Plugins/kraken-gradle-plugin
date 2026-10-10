package com.krakenplugins.gradle.internal;

import java.io.File;
import java.util.Locale;

/**
 * Locates the RuneLite installation the Kraken launcher installs into, using the same directories as the launcher.
 */
public final class RuneLiteInstallation {

    private RuneLiteInstallation() {
    }

    /**
     * Returns the default RuneLite installation directory for this operating system.
     *
     * @return {@code %LOCALAPPDATA%\RuneLite} on Windows, the app bundle's resources on macOS, or {@code null} elsewhere,
     * where the directory has to be configured
     */
    public static File defaultDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            return localAppData == null ? null : new File(localAppData, "RuneLite");
        }
        if (os.contains("mac")) {
            return new File("/Applications/RuneLite.app/Contents/Resources");
        }
        return null;
    }

    /**
     * @return whether the client runs on macOS
     */
    public static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Returns the java executable of the JRE bundled with RuneLite.
     *
     * @param runeliteDirectory the RuneLite installation directory
     * @return the executable, or {@code null} when the installation has no bundled JRE
     */
    public static File bundledJava(File runeliteDirectory) {
        File bin = new File(new File(runeliteDirectory, "jre"), "bin");
        for (String name : new String[]{"java.exe", "java"}) {
            File executable = new File(bin, name);
            if (executable.isFile()) {
                return executable;
            }
        }
        return null;
    }
}
