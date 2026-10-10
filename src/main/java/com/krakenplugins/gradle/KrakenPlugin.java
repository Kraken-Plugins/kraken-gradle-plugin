package com.krakenplugins.gradle;

import com.krakenplugins.gradle.internal.CompileLibrary;
import com.krakenplugins.gradle.internal.RuneLiteInstallation;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies {@code java} and adds the {@code kraken} extension with two tasks: {@code runKraken}, which launches the
 * installed Kraken client with this project's plugin sideloaded, and {@code krakenVersions}, which reports how the
 * Kraken API the project compiles against relates to the one the client runs.
 */
public class KrakenPlugin implements Plugin<Project> {

    public static final String EXTENSION_NAME = "kraken";
    public static final String RUN_TASK_NAME = "runKraken";
    public static final String VERSIONS_TASK_NAME = "krakenVersions";
    private static final String TASK_GROUP = "kraken";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);

        KrakenExtension extension = project.getExtensions().create(EXTENSION_NAME, KrakenExtension.class);
        File defaultRuneLiteDirectory = RuneLiteInstallation.defaultDirectory();
        if (defaultRuneLiteDirectory != null) {
            extension.getRuneliteDirectory().convention(project.getLayout().dir(project.provider(() -> defaultRuneLiteDirectory)));
        }
        File defaultKrakenDirectory = new File(new File(System.getProperty("user.home"), ".runelite"), "kraken");
        extension.getKrakenDirectory().convention(project.getLayout().dir(project.provider(() -> defaultKrakenDirectory)));
        extension.getClientArguments().convention(List.of("--developer-mode"));
        extension.getSideload().from(project.getTasks().named(JavaPlugin.JAR_TASK_NAME));

        Provider<List<String>> compileLibraries = project.getConfigurations()
                .named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)
                .flatMap(configuration -> configuration.getIncoming().getArtifacts().getResolvedArtifacts())
                .map(KrakenPlugin::describe);

        TaskProvider<RunKrakenTask> runKraken = project.getTasks().register(RUN_TASK_NAME, RunKrakenTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Launches the Kraken client with this plugin sideloaded.");
            task.getRuneliteDirectory().convention(extension.getRuneliteDirectory());
            task.getKrakenDirectory().convention(extension.getKrakenDirectory());
            task.getClientArguments().convention(extension.getClientArguments());
            task.getProfile().convention(extension.getProfile());
            task.getSideload().from(extension.getSideload());
            task.getCompileLibraries().convention(compileLibraries);
            task.getDebugJvm().convention(false);
        });
        skipWhenAnAncestorLaunches(project, runKraken);

        project.getTasks().register(VERSIONS_TASK_NAME, KrakenVersionsTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Compares the Kraken API this project compiles against with the one the Kraken client runs.");
            task.getKrakenDirectory().convention(extension.getKrakenDirectory());
            task.getCompileLibraries().convention(compileLibraries);
        });
    }

    /**
     * Running {@code runKraken} from a parent project also selects the {@code runKraken} of every subproject, which
     * would launch the client once per project, one after another. The parent's launch is the one that runs; it's the
     * one asked for, and a multi-plugin build typically sideloads its subprojects' jars there.
     */
    private static void skipWhenAnAncestorLaunches(Project project, TaskProvider<RunKrakenTask> runKraken) {
        String ownPath = project.absoluteProjectPath(RUN_TASK_NAME);
        List<String> ancestorPaths = new ArrayList<>();
        for (Project ancestor = project.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            ancestorPaths.add(ancestor.absoluteProjectPath(RUN_TASK_NAME));
        }
        if (ancestorPaths.isEmpty()) {
            return;
        }
        project.getGradle().getTaskGraph().whenReady(graph -> {
            if (!graph.hasTask(ownPath)) {
                return;
            }
            ancestorPaths.stream().filter(graph::hasTask).findFirst()
                    .ifPresent(ancestorPath -> runKraken.configure(task -> task.getLaunchedBy().set(ancestorPath)));
        });
    }

    private static List<String> describe(Set<ResolvedArtifactResult> artifacts) {
        return artifacts.stream().map(artifact -> {
            if (artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier) {
                ModuleComponentIdentifier module = (ModuleComponentIdentifier) artifact.getId().getComponentIdentifier();
                return CompileLibrary.describe(module.getGroup(), module.getModule(), module.getVersion(), artifact.getFile());
            }
            return CompileLibrary.describe(null, null, null, artifact.getFile());
        }).collect(Collectors.toList());
    }
}
