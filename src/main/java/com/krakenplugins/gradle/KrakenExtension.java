package com.krakenplugins.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code kraken { }} block.
 */
public abstract class KrakenExtension {

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
