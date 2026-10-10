package com.krakenplugins.gradle;

import com.krakenplugins.gradle.internal.VersionReport;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Prints the Kraken API and RuneLite versions the project compiles against and the Kraken API the installed client
 * runs, with a warning for each combination that can fail at runtime.
 */
@UntrackedTask(because = "It reports on files outside the build that change whenever the Kraken launcher updates.")
public abstract class KrakenVersionsTask extends DefaultTask {

    /**
     * @return the compile classpath, in {@link com.krakenplugins.gradle.internal.CompileLibrary} string form
     */
    @Internal
    public abstract ListProperty<String> getCompileLibraries();

    /**
     * @return Kraken's data directory
     */
    @Internal
    public abstract DirectoryProperty getKrakenDirectory();

    @TaskAction
    public void report() {
        VersionReport report = VersionReport.create(getCompileLibraries().get(), getKrakenDirectory().get().getAsFile());
        report.getLines().forEach(getLogger()::lifecycle);
        report.getWarnings().forEach(warning -> getLogger().warn("Warning: " + warning));
    }
}
