package com.krakenplugins.gradle;

import com.krakenplugins.gradle.internal.RepositoryManifest;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the unsigned external repository manifest for the plugin jars. {@code krakenRelease} signs it; run this task on
 * its own to inspect what a release would publish.
 */
@DisableCachingByDefault(because = "Writing the manifest is cheaper than fetching it from a cache.")
public abstract class KrakenManifestTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getJars();

    @Input
    public abstract Property<String> getRepositoryName();

    @Input
    public abstract Property<String> getRepositoryVersion();

    @Input
    @Optional
    public abstract Property<String> getRepositoryAuthor();

    @Input
    @Optional
    public abstract Property<String> getRepositoryDescription();

    @Input
    @Optional
    public abstract Property<String> getRepositoryWebsite();

    @Input
    @Optional
    public abstract Property<String> getArtifactBaseUrl();

    @Input
    public abstract MapProperty<String, String> getPluginNames();

    @OutputFile
    public abstract RegularFileProperty getManifest();

    @TaskAction
    public void write() throws IOException {
        String version = getRepositoryVersion().get();
        if ("unspecified".equals(version) || version.isEmpty()) {
            throw new GradleException("The release has no version. Set version in build.gradle, or kraken.release.version.");
        }
        if (!getArtifactBaseUrl().isPresent()) {
            throw new GradleException("The manifest needs to know where the plugin jars are downloaded from. Set"
                    + " kraken.release.github = 'owner/repository' to publish them as GitHub release assets, or"
                    + " kraken.release.artifactBaseUrl.");
        }
        List<File> jars = new ArrayList<>(getJars().getFiles());
        if (jars.isEmpty()) {
            throw new GradleException("There are no plugin jars to release. Set kraken.release.jars.");
        }
        jars.sort((left, right) -> left.getName().compareTo(right.getName()));

        RepositoryManifest.Metadata metadata = new RepositoryManifest.Metadata(getRepositoryName().get(), version,
                getRepositoryAuthor().getOrNull(), getRepositoryDescription().getOrNull(), getRepositoryWebsite().getOrNull(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        byte[] manifest = RepositoryManifest.write(metadata, jars, getArtifactBaseUrl().get(), getPluginNames().get());

        File output = getManifest().get().getAsFile();
        Files.createDirectories(output.getParentFile().toPath());
        Files.write(output.toPath(), manifest);
        getLogger().lifecycle("Wrote the manifest for " + jars.size() + " plugin" + (jars.size() == 1 ? "" : "s") + " to " + output + ".");
    }
}
