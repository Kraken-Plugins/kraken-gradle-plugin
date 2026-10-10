package com.krakenplugins.gradle.internal;

import java.io.File;

/**
 * One resolved compile classpath entry, carried between configuration and execution as a plain string so tasks stay
 * compatible with the configuration cache. The string form is {@code group:name:version=path}, with empty coordinates
 * for file dependencies.
 */
public final class CompileLibrary {

    private static final String KRAKEN_API = "com.github.kraken:kraken-api";
    private static final String RUNELITE_CLIENT = "net.runelite:client";

    private final String module;
    private final String version;
    private final File file;

    private CompileLibrary(String module, String version, File file) {
        this.module = module;
        this.version = version;
        this.file = file;
    }

    /**
     * Describes a classpath entry.
     *
     * @param group   the module group, or {@code null} for a file dependency
     * @param name    the module name, or {@code null} for a file dependency
     * @param version the module version, or {@code null} for a file dependency
     * @param file    the resolved file
     * @return the string form
     */
    public static String describe(String group, String name, String version, File file) {
        String coordinates = group == null ? "::" : group + ":" + name + ":" + version;
        return coordinates + "=" + file.getAbsolutePath();
    }

    /**
     * Parses the string form produced by {@link #describe}.
     *
     * @param description the string form
     * @return the classpath entry
     */
    public static CompileLibrary parse(String description) {
        int separator = description.indexOf('=');
        String[] coordinates = description.substring(0, separator).split(":", -1);
        File file = new File(description.substring(separator + 1));
        String module = coordinates[0].isEmpty() ? null : coordinates[0] + ":" + coordinates[1];
        String version = coordinates[2].isEmpty() ? null : coordinates[2];
        return new CompileLibrary(module, version, file);
    }

    /**
     * Matches the Kraken API by its coordinates, or by file name for a file dependency.
     *
     * @return whether this entry is the Kraken API
     */
    public boolean isKrakenApi() {
        return KRAKEN_API.equals(module) || (module == null && ApiTarget.versionFromFileName(file.getName()) != null);
    }

    /**
     * @return whether this entry is the RuneLite client
     */
    public boolean isRuneLiteClient() {
        return RUNELITE_CLIENT.equals(module);
    }

    public String getVersion() {
        return version;
    }

    public File getFile() {
        return file;
    }
}
