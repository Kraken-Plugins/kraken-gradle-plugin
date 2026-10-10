package com.krakenplugins.gradle;

import com.krakenplugins.gradle.internal.LaunchCommand;
import com.krakenplugins.gradle.internal.RuneLiteInstallation;
import com.krakenplugins.gradle.internal.VersionReport;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.tasks.options.Option;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Launches the installed Kraken client the way the Kraken launcher does, with this project's plugin jars sideloaded.
 * The client downloads the signed Kraken bootstrap, or uses the saved copy when offline, so it runs the Kraken API and
 * Kraken-Client built for the current RuneLite rather than the API the project compiles against.
 */
@UntrackedTask(because = "It launches the game client.")
public abstract class RunKrakenTask extends DefaultTask {

    /**
     * @return the RuneLite installation directory
     */
    @Internal
    public abstract DirectoryProperty getRuneliteDirectory();

    /**
     * @return Kraken's data directory
     */
    @Internal
    public abstract DirectoryProperty getKrakenDirectory();

    /**
     * @return the arguments passed to RuneLite
     */
    @Internal
    public abstract ListProperty<String> getClientArguments();

    /**
     * @return the Jagex profile to log in as
     */
    @Internal
    @Option(option = "profile", description = "The linked Jagex profile to log in as.")
    public abstract Property<String> getProfile();

    /**
     * @return the plugin jars and directories of jars to sideload
     */
    @InputFiles
    public abstract ConfigurableFileCollection getSideload();

    /**
     * @return the compile classpath, in {@link com.krakenplugins.gradle.internal.CompileLibrary} string form
     */
    @Internal
    public abstract ListProperty<String> getCompileLibraries();

    /**
     * @return whether the client waits for a debugger before starting
     */
    @Internal
    @Option(option = "debug-jvm", description = "Waits for a debugger to attach on port 5005 before starting the client.")
    public abstract Property<Boolean> getDebugJvm();

    /**
     * @return the path of the parent project's {@code runKraken} that launches the client instead of this task, when
     * both run in the same build
     */
    @Internal
    public abstract Property<String> getLaunchedBy();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Inject
    protected abstract JavaToolchainService getJavaToolchainService();

    @TaskAction
    public void run() {
        if (getLaunchedBy().isPresent()) {
            getLogger().lifecycle("Skipping: " + getLaunchedBy().get() + " launches the client.");
            return;
        }
        File runeliteDirectory = requireInstallation();

        VersionReport report = VersionReport.create(getCompileLibraries().get(), getKrakenDirectory().get().getAsFile());
        report.getLines().forEach(getLogger()::lifecycle);
        report.getWarnings().forEach(warning -> getLogger().warn("Warning: " + warning));

        List<File> sideloadPaths = new ArrayList<>(getSideload().getFiles());
        sideloadPaths.forEach(path -> getLogger().lifecycle("Sideloading " + path));
        boolean debug = getDebugJvm().getOrElse(false);
        if (debug) {
            getLogger().lifecycle("Waiting for a debugger to attach on port 5005.");
        }

        File java = javaExecutable(runeliteDirectory);
        getExecOperations().javaexec(spec -> {
            spec.setExecutable(java);
            spec.setWorkingDir(runeliteDirectory);
            spec.classpath(new File(runeliteDirectory, LaunchCommand.RUNELITE_JAR));
            spec.getMainClass().set(LaunchCommand.MAIN_CLASS);
            spec.jvmArgs(LaunchCommand.jvmArguments(runeliteDirectory, sideloadPaths, debug, RuneLiteInstallation.isMac()));
            spec.args(LaunchCommand.programArguments(getClientArguments().get(), getProfile().getOrNull()));
        });
    }

    /**
     * Fails with installation instructions unless the RuneLite directory holds both RuneLite's launcher and the Kraken
     * agent.
     */
    private File requireInstallation() {
        if (!getRuneliteDirectory().isPresent()) {
            throw new GradleException("The RuneLite installation directory is unknown on this operating system. "
                    + "Set kraken { runeliteDirectory = file('/path/to/RuneLite') } to the folder holding RuneLite.jar.");
        }
        File directory = getRuneliteDirectory().get().getAsFile();
        for (String required : new String[]{LaunchCommand.RUNELITE_JAR, LaunchCommand.KRAKEN_AGENT_JAR}) {
            if (!new File(directory, required).isFile()) {
                throw new GradleException(required + " was not found in " + directory + ". Install the Kraken launcher "
                        + "from https://kraken-plugins.com, or set kraken { runeliteDirectory } to your RuneLite installation.");
            }
        }
        return directory;
    }

    /**
     * Uses RuneLite's bundled JRE, which is what players run, and falls back to a Java 17 toolchain.
     */
    private File javaExecutable(File runeliteDirectory) {
        File bundled = RuneLiteInstallation.bundledJava(runeliteDirectory);
        if (bundled != null) {
            return bundled;
        }
        return getJavaToolchainService()
                .launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(17)))
                .get()
                .getExecutablePath()
                .getAsFile();
    }
}
