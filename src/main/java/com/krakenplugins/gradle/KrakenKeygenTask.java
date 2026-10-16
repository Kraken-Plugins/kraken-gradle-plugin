package com.krakenplugins.gradle;

import com.krakenplugins.gradle.internal.ManifestSigner;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.tasks.options.Option;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;

/**
 * Creates an Ed25519 key for signing the repository's manifests. The key is written outside the project by default so it
 * can't be committed, and never over an existing file: losing or replacing a key means every user who pinned it is asked
 * to review the next release.
 */
@UntrackedTask(because = "It creates a new key every time.")
public abstract class KrakenKeygenTask extends DefaultTask {

    /**
     * @return where to write the private key
     */
    @Internal
    public abstract Property<String> getOutput();

    /**
     * Sets where to write the private key.
     *
     * @param output the file path
     */
    @Option(option = "output", description = "Where to write the private key PEM.")
    public void setOutputOption(String output) {
        getOutput().set(output);
    }

    @TaskAction
    public void generate() throws IOException {
        File output = new File(getOutput().get());
        if (output.exists()) {
            throw new GradleException(output + " already exists. Choose another file with --output; replacing a key that"
                    + " users pinned makes all of them review the next release.");
        }

        ManifestSigner.GeneratedKey key;
        try {
            key = ManifestSigner.generate();
        } catch (GeneralSecurityException e) {
            throw new GradleException("Could not create a key: " + e.getMessage(), e);
        }

        if (output.getParentFile() != null) {
            Files.createDirectories(output.getParentFile().toPath());
        }
        Files.write(output.toPath(), key.getPrivateKeyPem().getBytes(StandardCharsets.UTF_8));
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(output.toPath(), PosixFilePermissions.fromString("rw-------"));
        }

        getLogger().lifecycle("Wrote a new signing key to " + output + ".");
        getLogger().lifecycle("Fingerprint: " + key.getFingerprint() + " (users see this when they add the repository).");
        getLogger().lifecycle("Store it as the " + KrakenReleaseTask.SIGNING_KEY_VARIABLE + " secret for CI, for example:");
        getLogger().lifecycle("  gh secret set " + KrakenReleaseTask.SIGNING_KEY_VARIABLE + " < \"" + output + "\"");
        getLogger().lifecycle("Keep a backup somewhere safe. Never commit it.");
    }
}
