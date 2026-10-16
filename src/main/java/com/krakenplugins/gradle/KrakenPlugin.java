package com.krakenplugins.gradle;

import com.krakenplugins.gradle.internal.CompileLibrary;
import com.krakenplugins.gradle.internal.RuneLiteInstallation;
import com.krakenplugins.gradle.internal.RuntimeApi;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Applies {@code java} and adds the {@code kraken} extension, which adds the Kraken API, RuneLite and Lombok dependencies
 * it's given versions for, and these tasks:
 * <ul>
 *     <li>{@code runKraken} launches the installed Kraken client with this project's plugin sideloaded;</li>
 *     <li>{@code krakenVersions} reports how the Kraken API the project compiles against relates to the one the client
 *     runs;</li>
 *     <li>{@code krakenCheck}, part of {@code check}, verifies the plugin jar against the Kraken API the client runs;</li>
 *     <li>{@code krakenManifest}, {@code krakenRelease} and {@code krakenKeygen} build and sign an external repository
 *     release. They belong to the root project when it applies this plugin, and to each project otherwise.</li>
 * </ul>
 */
public class KrakenPlugin implements Plugin<Project> {

    public static final String EXTENSION_NAME = "kraken";
    public static final String RUN_TASK_NAME = "runKraken";
    public static final String VERSIONS_TASK_NAME = "krakenVersions";
    public static final String CHECK_TASK_NAME = "krakenCheck";
    public static final String MANIFEST_TASK_NAME = "krakenManifest";
    public static final String RELEASE_TASK_NAME = "krakenRelease";
    public static final String KEYGEN_TASK_NAME = "krakenKeygen";
    static final String PLUGIN_ID = "com.krakenplugins.plugin";
    static final String KRAKEN_REPOSITORY = "https://repo.kraken-plugins.com";
    static final String RUNELITE_REPOSITORY = "https://repo.runelite.net";
    static final String KRAKEN_API_GROUP = "com.github.kraken";
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

        extension.getAddRepositories().convention(true);
        extension.getBootstrapUrl().convention(RuntimeApi.PRODUCTION_BOOTSTRAP);
        addDependencies(project, extension);
        project.afterEvaluate(evaluated -> {
            if (extension.getAddRepositories().get()) {
                addRepositories(project.getRepositories());
            }
        });

        File apiCache = new File(project.getGradle().getGradleUserHomeDir(), "caches/kraken-plugin/api");
        Provider<RuntimeApiService> runtimeApi = project.getGradle().getSharedServices().registerIfAbsent(
                RuntimeApiService.NAME, RuntimeApiService.class, spec -> spec.getParameters().getCacheDirectory().set(apiCache));
        boolean offline = project.getGradle().getStartParameter().isOffline();
        TaskProvider<KrakenCheckTask> krakenCheck = project.getTasks().register(CHECK_TASK_NAME, KrakenCheckTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Checks the plugin jar against the Kraken API the Kraken client runs.");
            SourceSet main = project.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            task.getClasses().from(main.getOutput().getClassesDirs());
            task.getJar().convention(project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class).flatMap(Jar::getArchiveFile));
            task.getBootstrapUrl().convention(extension.getBootstrapUrl());
            task.getRuntimeApiService().set(runtimeApi);
            task.usesService(runtimeApi);
            task.getKrakenDirectory().convention(extension.getKrakenDirectory());
            task.getOffline().convention(offline);
            task.getCompileLibraries().convention(compileLibraries);
        });
        project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME, check -> check.dependsOn(krakenCheck));

        Project root = project.getRootProject();
        if (project == root || !root.getPluginManager().hasPlugin(PLUGIN_ID)) {
            registerReleaseTasks(project, extension, runtimeApi, offline);
        }
    }

    /**
     * Adds the dependencies whose versions the {@code kraken} block sets. They're added lazily, so the block can come
     * after the plugin is applied.
     */
    private static void addDependencies(Project project, KrakenExtension extension) {
        DependencyHandler dependencies = project.getDependencies();
        Provider<List<Dependency>> api = extension.getApiVersion()
                .map(version -> List.of(dependencies.create(KRAKEN_API_GROUP + ":kraken-api:" + version)));
        Provider<List<Dependency>> runelite = extension.getRuneliteVersion()
                .map(version -> List.of(dependencies.create("net.runelite:client:" + version)));
        Provider<List<Dependency>> lombok = extension.getLombokVersion()
                .map(version -> List.of(dependencies.create("org.projectlombok:lombok:" + version)));

        for (String configuration : List.of(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME)) {
            addLater(project, configuration, api);
            addLater(project, configuration, runelite);
        }
        for (String configuration : List.of(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME,
                JavaPlugin.TEST_COMPILE_ONLY_CONFIGURATION_NAME, JavaPlugin.TEST_ANNOTATION_PROCESSOR_CONFIGURATION_NAME)) {
            addLater(project, configuration, lombok);
        }
    }

    private static void addLater(Project project, String configuration, Provider<List<Dependency>> dependencies) {
        project.getConfigurations().named(configuration, c ->
                c.getDependencies().addAllLater(dependencies.orElse(Collections.emptyList())));
    }

    /**
     * Adds the repositories the Kraken API and RuneLite resolve from. Each is limited to the groups it serves, so a
     * dependency is never looked up in, or taken from, the wrong one.
     */
    private static void addRepositories(RepositoryHandler repositories) {
        repositories.maven(repository -> {
            repository.setName("Kraken");
            repository.setUrl(KRAKEN_REPOSITORY);
            repository.content(content -> content.includeGroup(KRAKEN_API_GROUP));
        });
        repositories.maven(repository -> {
            repository.setName("RuneLite");
            repository.setUrl(RUNELITE_REPOSITORY);
            repository.content(content -> content.excludeGroup(KRAKEN_API_GROUP));
        });
        repositories.mavenCentral(repository -> repository.content(content -> content.excludeGroup(KRAKEN_API_GROUP)));
    }

    private static void registerReleaseTasks(Project project, KrakenExtension extension, Provider<RuntimeApiService> runtimeApi,
                                             boolean offline) {
        KrakenRelease release = extension.getRelease();
        release.getName().convention(project.getName());
        release.getVersion().convention(project.provider(() -> String.valueOf(project.getVersion())));
        release.getTag().convention(release.getVersion().map(version -> "v" + version));
        release.getWebsite().convention(release.getGithub().map(github -> "https://github.com/" + github));
        release.getArtifactBaseUrl().convention(release.getGithub().zip(release.getTag(),
                (github, tag) -> "https://github.com/" + github + "/releases/download/" + tag + "/"));
        release.getJars().from(project.files((Callable<Object>) () -> defaultReleaseJars(project)));

        TaskProvider<KrakenManifestTask> manifest = project.getTasks().register(MANIFEST_TASK_NAME, KrakenManifestTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Writes the unsigned external repository manifest for the plugin jars.");
            task.getJars().from(release.getJars());
            task.getRepositoryName().convention(release.getName());
            task.getRepositoryVersion().convention(release.getVersion());
            task.getRepositoryAuthor().convention(release.getAuthor());
            task.getRepositoryDescription().convention(release.getDescription());
            task.getRepositoryWebsite().convention(release.getWebsite());
            task.getArtifactBaseUrl().convention(release.getArtifactBaseUrl());
            task.getPluginNames().convention(release.getPluginNames());
            task.getManifest().convention(project.getLayout().getBuildDirectory().file("kraken/manifest.json"));
        });

        project.getTasks().register(RELEASE_TASK_NAME, KrakenReleaseTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Checks the plugin jars and writes them with a signed manifest to build/kraken-release.");
            task.getJars().from(release.getJars());
            task.getManifest().convention(manifest.flatMap(KrakenManifestTask::getManifest));
            task.getBootstrapUrl().convention(extension.getBootstrapUrl());
            task.getRuntimeApiService().set(runtimeApi);
            task.usesService(runtimeApi);
            task.getKrakenDirectory().convention(extension.getKrakenDirectory());
            task.getOffline().convention(offline);
            task.getOutputDirectory().convention(project.getLayout().getBuildDirectory().dir("kraken-release"));
            task.getFingerprintFile().convention(project.getLayout().getBuildDirectory().file("kraken/signing-key-fingerprint.txt"));
        });

        File defaultKey = new File(new File(new File(System.getProperty("user.home"), ".kraken"), "keys"), project.getName() + ".pem");
        project.getTasks().register(KEYGEN_TASK_NAME, KrakenKeygenTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Creates an Ed25519 key for signing the repository's manifests.");
            task.getOutput().convention(defaultKey.getAbsolutePath());
        });
    }

    /**
     * The jars of every subproject that applies this plugin, or this project's own jar when none does.
     */
    private static List<Object> defaultReleaseJars(Project project) {
        List<Object> jars = new ArrayList<>();
        for (Project subproject : project.getSubprojects()) {
            if (subproject.getPluginManager().hasPlugin(PLUGIN_ID)) {
                jars.add(subproject.getTasks().named(JavaPlugin.JAR_TASK_NAME));
            }
        }
        if (jars.isEmpty()) {
            jars.add(project.getTasks().named(JavaPlugin.JAR_TASK_NAME));
        }
        return jars;
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
