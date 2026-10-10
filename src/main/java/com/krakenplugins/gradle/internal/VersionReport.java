package com.krakenplugins.gradle.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compares the Kraken API and RuneLite a project compiles against with the Kraken API the installed client runs.
 * Kraken API releases stay backwards compatible, so compiling against an older API than the client runs is fine. The
 * report warns about the two cases that break at runtime: compiling against a newer API than the client runs, and an API
 * whose hooks target a different RuneLite than the project compiles against, which only matters for runs that put the
 * compile-time API on the classpath.
 */
public final class VersionReport {

    private final List<String> lines;
    private final List<String> warnings;

    private VersionReport(List<String> lines, List<String> warnings) {
        this.lines = Collections.unmodifiableList(lines);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    /**
     * Builds the report from the project's compile classpath and the installed client's saved bootstrap.
     *
     * @param compileLibraries the compile classpath, as produced by {@link CompileLibrary#describe}
     * @param krakenDirectory  Kraken's data directory, normally {@code ~/.runelite/kraken}
     * @return the report
     */
    public static VersionReport create(List<String> compileLibraries, File krakenDirectory) {
        ApiTarget compileApi = null;
        String compileRuneLite = null;
        for (String description : compileLibraries) {
            CompileLibrary library = CompileLibrary.parse(description);
            if (library.isKrakenApi()) {
                compileApi = ApiTarget.fromJar(library.getFile());
            } else if (library.isRuneLiteClient()) {
                compileRuneLite = library.getVersion();
            }
        }
        return create(compileApi, compileRuneLite, KrakenBootstrap.runtimeApi(krakenDirectory), krakenDirectory);
    }

    /**
     * Builds the report from already resolved versions.
     *
     * @param compileApi      the Kraken API on the compile classpath, or {@code null} when there is none
     * @param compileRuneLite the RuneLite client version on the compile classpath, or {@code null} when there is none
     * @param runtimeApi      the Kraken API the installed client runs, or {@code null} when unknown
     * @param krakenDirectory Kraken's data directory, named in the message when the runtime API is unknown
     * @return the report
     */
    static VersionReport create(ApiTarget compileApi, String compileRuneLite, ApiTarget runtimeApi, File krakenDirectory) {
        List<String> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        lines.add("Compiling against Kraken API " + (compileApi == null ? "(none on the compile classpath)" : compileApi.describe())
                + (compileRuneLite == null ? "" : " and RuneLite " + compileRuneLite) + ".");
        if (runtimeApi == null) {
            lines.add("The Kraken API the client runs is unknown: there is no saved bootstrap in "
                    + new File(krakenDirectory, KrakenBootstrap.REPOSITORY_DIRECTORY)
                    + ". Launch the Kraken client once while online to create it.");
        } else {
            lines.add("The Kraken client runs Kraken API " + runtimeApi.describe() + ".");
        }

        if (compileApi != null && runtimeApi != null && compileApi.getApiVersion() != null && runtimeApi.getApiVersion() != null
                && Versions.compare(compileApi.getApiVersion(), runtimeApi.getApiVersion()) > 0) {
            warnings.add("Kraken API " + compileApi.getApiVersion() + " is newer than the " + runtimeApi.getApiVersion()
                    + " the client runs, so anything added since " + runtimeApi.getApiVersion()
                    + " throws NoSuchMethodError in the client. Launch the client while online to update it, or compile against "
                    + runtimeApi.getApiVersion() + ".");
        }

        if (compileApi != null && compileApi.getRuneliteVersion() != null && compileRuneLite != null
                && !compileApi.getRuneliteVersion().equals(compileRuneLite)) {
            warnings.add("Kraken API " + compileApi.getApiVersion() + " has hooks for RuneLite " + compileApi.getRuneliteVersion()
                    + " but this project compiles against RuneLite " + compileRuneLite
                    + ". runKraken is unaffected because the client brings its own API, but IDE or test runs that put kraken-api"
                    + " on the classpath use hooks for " + compileApi.getRuneliteVersion() + " and may fail to log in or interact.");
        }
        return new VersionReport(lines, warnings);
    }

    public List<String> getLines() {
        return lines;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
