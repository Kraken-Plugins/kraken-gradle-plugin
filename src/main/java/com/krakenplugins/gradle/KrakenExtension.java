package com.krakenplugins.gradle;

import org.gradle.api.Action;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * The {@code kraken { }} block.
 */
public abstract class KrakenExtension {

    private final KrakenRelease release;

    @Inject
    public KrakenExtension(ObjectFactory objects) {
        this.release = objects.newInstance(KrakenRelease.class);
    }

    /**
     * The Kraken API version to compile and test against. When set, {@code com.github.kraken:kraken-api} is added to
     * {@code compileOnly} and {@code testImplementation}. The client brings its own API at runtime, and
     * {@code krakenCheck} verifies the plugin against that one.
     *
     * @return the version property
     */
    public abstract Property<String> getApiVersion();

    /**
     * The RuneLite version to compile and test against. When set, {@code net.runelite:client} is added to
     * {@code compileOnly} and {@code testImplementation}.
     *
     * @return the version property
     */
    public abstract Property<String> getRuneliteVersion();

    /**
     * The Lombok version to use. When set, Lombok is added as {@code compileOnly} and as an annotation processor, for
     * main and test sources.
     *
     * @return the version property
     */
    public abstract Property<String> getLombokVersion();

    /**
     * Whether to add the Kraken, RuneLite and Maven Central repositories the dependencies above resolve from. Defaults
     * to {@code true}; turn it off when the build declares its repositories in {@code settings.gradle}.
     *
     * @return the flag property
     */
    public abstract Property<Boolean> getAddRepositories();

    /**
     * The Kraken bootstrap that names the Kraken API the client runs, which {@code krakenCheck} and
     * {@code krakenRelease} check plugins against. Defaults to the production bootstrap.
     *
     * @return the URL property
     */
    public abstract Property<String> getBootstrapUrl();

    /**
     * @return the external repository that {@code krakenManifest} and {@code krakenRelease} build
     */
    public KrakenRelease getRelease() {
        return release;
    }

    /**
     * Configures the external repository that {@code krakenManifest} and {@code krakenRelease} build.
     *
     * @param action the configuration
     */
    public void release(Action<? super KrakenRelease> action) {
        action.execute(release);
    }

    /**
     * The RuneLite installation the Kraken launcher installed into, holding {@code RuneLite.jar},
     * {@code KrakenSetup.jar} and the bundled {@code jre}. Defaults to {@code %LOCALAPPDATA%\RuneLite} on Windows and
     * {@code /Applications/RuneLite.app/Contents/Resources} on macOS, and must be set elsewhere.
     *
     * @return the directory property
     */
    public abstract DirectoryProperty getRuneliteDirectory();

    /**
     * Kraken's data directory, holding the bootstrap and jars the launcher saved. Defaults to {@code ~/.runelite/kraken}.
     *
     * @return the directory property
     */
    public abstract DirectoryProperty getKrakenDirectory();

    /**
     * Arguments passed to RuneLite by {@code runKraken}. Defaults to {@code --developer-mode}.
     *
     * @return the arguments property
     */
    public abstract ListProperty<String> getClientArguments();

    /**
     * A Jagex profile linked with the Profiles plugin to log in as, passed as {@code --kraken-profile}.
     *
     * @return the profile property
     */
    public abstract Property<String> getProfile();

    /**
     * Plugin jars, and directories of jars, that {@code runKraken} sideloads. Defaults to this project's {@code jar}
     * output; add the jars of other projects to run several plugins together.
     *
     * @return the file collection
     */
    public abstract ConfigurableFileCollection getSideload();
}
