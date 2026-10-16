package com.krakenplugins.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code kraken { release { } }} block, describing the external repository that {@code krakenManifest} and
 * {@code krakenRelease} build.
 */
public abstract class KrakenRelease {

    /**
     * The repository name users see when they add it. Defaults to the project name.
     *
     * @return the name property
     */
    public abstract Property<String> getName();

    /**
     * The release version. Defaults to the project version.
     *
     * @return the version property
     */
    public abstract Property<String> getVersion();

    /**
     * The author shown with the repository. Optional.
     *
     * @return the author property
     */
    public abstract Property<String> getAuthor();

    /**
     * A description of the repository. Optional.
     *
     * @return the description property
     */
    public abstract Property<String> getDescription();

    /**
     * The repository's website. Defaults to the GitHub repository when {@link #getGithub()} is set.
     *
     * @return the website property
     */
    public abstract Property<String> getWebsite();

    /**
     * The GitHub repository releases are published to, as {@code owner/name}. Sets the default
     * {@link #getArtifactBaseUrl()} to the release's download URL.
     *
     * @return the GitHub repository property
     */
    public abstract Property<String> getGithub();

    /**
     * The git tag of the release. Defaults to {@code v<version>}.
     *
     * @return the tag property
     */
    public abstract Property<String> getTag();

    /**
     * The URL each plugin jar's file name is appended to. Defaults to
     * {@code https://github.com/<github>/releases/download/<tag>/}.
     *
     * @return the base URL property
     */
    public abstract Property<String> getArtifactBaseUrl();

    /**
     * The plugin jars to publish. Defaults to the jars of every subproject that applies the Kraken plugin, or this
     * project's jar when there are none.
     *
     * @return the jars
     */
    public abstract ConfigurableFileCollection getJars();

    /**
     * Manifest names to use instead of the ones derived from jar names, keyed by the jar's base name. The client tracks
     * the plugins a user approved by these names, so changing one makes users review the plugin again.
     *
     * @return the names property
     */
    public abstract MapProperty<String, String> getPluginNames();
}
