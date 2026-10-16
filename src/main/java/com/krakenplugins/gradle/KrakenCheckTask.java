package com.krakenplugins.gradle;

import com.krakenplugins.gradle.internal.ApiSurface;
import com.krakenplugins.gradle.internal.ApiTarget;
import com.krakenplugins.gradle.internal.CompileLibrary;
import com.krakenplugins.gradle.internal.PluginJarCheck;
import com.krakenplugins.gradle.internal.RuntimeApi;
import com.krakenplugins.gradle.internal.Versions;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Checks the plugin jar against the Kraken API the client runs, so a call to an API method the client doesn't have
 * fails the build instead of throwing {@code NoSuchMethodError} for users. See {@link PluginJarCheck} for what it checks.
 */
@UntrackedTask(because = "The Kraken API the client runs changes whenever a new Kraken bootstrap is published.")
public abstract class KrakenCheckTask extends DefaultTask {

    /**
     * @return the compiled classes; the check is skipped when there are none
     */
    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    public abstract ConfigurableFileCollection getClasses();

    /**
     * @return the plugin jar
     */
    @InputFile
    public abstract RegularFileProperty getJar();

    /**
     * @return the bootstrap naming the Kraken API the client runs
     */
    @Input
    public abstract Property<String> getBootstrapUrl();

    /**
     * @return the service that finds the Kraken API the client runs
     */
    @Internal
    public abstract Property<RuntimeApiService> getRuntimeApiService();

    /**
     * @return Kraken's data directory, used when the bootstrap can't be fetched
     */
    @Internal
    public abstract DirectoryProperty getKrakenDirectory();

    /**
     * @return whether Gradle runs offline
     */
    @Internal
    public abstract Property<Boolean> getOffline();

    /**
     * @return the compile classpath, in {@link CompileLibrary} string form, named in the hint when the check fails
     */
    @Internal
    public abstract ListProperty<String> getCompileLibraries();

    @TaskAction
    public void check() throws IOException {
        RuntimeApi runtime = getRuntimeApiService().get().resolve(getBootstrapUrl().get(),
                getKrakenDirectory().get().getAsFile(), getOffline().get(), warning -> getLogger().warn("Warning: " + warning));
        if (runtime == null) {
            getLogger().warn("Warning: skipping the Kraken API check. Gradle is offline and the Kraken launcher has no saved"
                    + " bootstrap to check against.");
            return;
        }
        checkJars(Collections.singletonList(getJar().get().getAsFile()), runtime, compileApiVersion(), getLogger());
    }

    /**
     * Checks plugin jars against a runtime API, logging warnings and a summary, and fails when any jar has errors.
     *
     * @param jars              the plugin jars
     * @param runtime           the Kraken API the client runs
     * @param compileApiVersion the Kraken API version the jars were compiled against, or {@code null} when unknown
     * @param logger            where to log
     * @throws IOException when a jar can't be read
     */
    static void checkJars(List<File> jars, RuntimeApi runtime, String compileApiVersion, Logger logger) throws IOException {
        String runtimeVersion = runtime.getTarget().getApiVersion();
        ApiSurface api = ApiSurface.read(runtime.getJar());
        if (api.size() == 0) {
            throw new GradleException(runtime.getJar() + " from " + runtime.getSource() + " holds no Kraken API classes.");
        }

        List<String> errors = new ArrayList<>();
        int checked = 0;
        for (File jar : jars) {
            PluginJarCheck result = PluginJarCheck.check(jar, api, runtimeVersion);
            result.getWarnings().forEach(warning -> logger.warn("Warning: " + warning));
            errors.addAll(result.getErrors());
            checked += result.getCheckedReferences();
        }

        if (!errors.isEmpty()) {
            StringBuilder message = new StringBuilder();
            message.append(jars.size() == 1 ? jars.get(0).getName() + " won't work" : "These plugins won't work")
                    .append(" in the Kraken client, which runs Kraken API ").append(runtime.getTarget().describe())
                    .append(" (from ").append(runtime.getSource()).append("):");
            errors.forEach(error -> message.append("\n  - ").append(error));
            if (compileApiVersion != null && runtimeVersion != null && Versions.compare(compileApiVersion, runtimeVersion) > 0) {
                message.append("\nThe build compiles against Kraken API ").append(compileApiVersion)
                        .append(", which is newer. Compile against ").append(runtimeVersion)
                        .append(" (kraken.apiVersion) to see these as compile errors, or wait for the client to update.");
            }
            throw new GradleException(message.toString());
        }
        logger.lifecycle("Checked " + checked + " Kraken API references in " + describe(jars) + " against Kraken API "
                + runtime.getTarget().describe() + " from " + runtime.getSource() + ".");
    }

    private String compileApiVersion() {
        for (String description : getCompileLibraries().getOrElse(Collections.emptyList())) {
            CompileLibrary library = CompileLibrary.parse(description);
            if (library.isKrakenApi()) {
                return library.getVersion() != null ? library.getVersion()
                        : ApiTarget.fromJar(library.getFile()).getApiVersion();
            }
        }
        return null;
    }

    private static String describe(List<File> jars) {
        return jars.size() == 1 ? jars.get(0).getName() : jars.size() + " plugin jars";
    }
}
