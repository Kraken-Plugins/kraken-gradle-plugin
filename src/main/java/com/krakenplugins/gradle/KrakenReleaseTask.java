package com.krakenplugins.gradle;

import com.krakenplugins.gradle.internal.ManifestSigner;
import com.krakenplugins.gradle.internal.RuntimeApi;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

/**
 * Prepares a release in one directory: checks every plugin jar against the Kraken API the client runs, copies the jars,
 * and signs the manifest with the Ed25519 key in the {@code KRAKEN_SIGNING_KEY} environment variable (or the file
 * {@code KRAKEN_SIGNING_KEY_FILE} names). Publishing the directory, for example as GitHub release assets, is left to CI.
 */
@UntrackedTask(because = "It checks against the published Kraken bootstrap and signs with a key from the environment.")
public abstract class KrakenReleaseTask extends DefaultTask {

    static final String SIGNING_KEY_VARIABLE = "KRAKEN_SIGNING_KEY";
    static final String SIGNING_KEY_FILE_VARIABLE = "KRAKEN_SIGNING_KEY_FILE";

    @InputFiles
    public abstract ConfigurableFileCollection getJars();

    /**
     * @return the unsigned manifest {@code krakenManifest} wrote
     */
    @InputFile
    public abstract RegularFileProperty getManifest();

    @Input
    public abstract Property<String> getBootstrapUrl();

    /**
     * @return the service that finds the Kraken API the client runs
     */
    @Internal
    public abstract Property<RuntimeApiService> getRuntimeApiService();

    @Internal
    public abstract DirectoryProperty getKrakenDirectory();

    @Internal
    public abstract Property<Boolean> getOffline();

    /**
     * @return the directory holding the jars and the signed {@code manifest.json}
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * @return a file the signing key's fingerprint is written to, for CI to put in release notes
     */
    @OutputFile
    public abstract RegularFileProperty getFingerprintFile();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void release() throws IOException {
        List<File> jars = new ArrayList<>(getJars().getFiles());
        RuntimeApi runtime = getRuntimeApiService().get().resolve(getBootstrapUrl().get(),
                getKrakenDirectory().get().getAsFile(), getOffline().get(), warning -> getLogger().warn("Warning: " + warning));
        if (runtime == null) {
            throw new GradleException("A release must be checked against the Kraken API the client runs, but Gradle is offline"
                    + " and the Kraken launcher has no saved bootstrap. Run without --offline.");
        }
        KrakenCheckTask.checkJars(jars, runtime, null, getLogger());

        String signingKey = signingKey();
        ManifestSigner.Signed signed;
        try {
            signed = ManifestSigner.sign(Files.readAllBytes(getManifest().get().getAsFile().toPath()), signingKey);
        } catch (GeneralSecurityException e) {
            throw new GradleException("Could not sign the manifest: " + e.getMessage(), e);
        }

        File output = getOutputDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(output));
        Files.createDirectories(output.toPath());
        for (File jar : jars) {
            Files.copy(jar.toPath(), new File(output, jar.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(new File(output, "manifest.json").toPath(), signed.getEnvelope());
        File fingerprint = getFingerprintFile().get().getAsFile();
        Files.write(fingerprint.toPath(), (signed.getFingerprint() + "\n").getBytes(StandardCharsets.UTF_8));

        getLogger().lifecycle("Release ready in " + output + ": " + jars.size() + " plugin jar" + (jars.size() == 1 ? "" : "s")
                + " and manifest.json, signed with key " + signed.getFingerprint() + ".");
    }

    private String signingKey() throws IOException {
        String key = System.getenv(SIGNING_KEY_VARIABLE);
        if (key != null && !key.isBlank()) {
            return key;
        }
        String keyFile = System.getenv(SIGNING_KEY_FILE_VARIABLE);
        if (keyFile != null && !keyFile.isBlank()) {
            return Files.readString(new File(keyFile).toPath());
        }
        throw new GradleException("The manifest must be signed, but neither " + SIGNING_KEY_VARIABLE + " (the private key PEM) nor "
                + SIGNING_KEY_FILE_VARIABLE + " (a path to it) is set. Users who added the repository pinned its key, so an"
                + " unsigned manifest would make every one of them review the update. Create a key with krakenKeygen.");
    }
}
