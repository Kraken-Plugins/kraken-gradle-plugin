package com.krakenplugins.gradle.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Kraken bootstrap the launcher saved on its last online launch, which names the Kraken API jar the client
 * runs. The launcher keeps the bootstrap and the jars it lists in {@code <kraken directory>/repository2}.
 */
public final class KrakenBootstrap {

    static final String REPOSITORY_DIRECTORY = "repository2";
    static final String BOOTSTRAP_FILE = "bootstrap.json";
    private static final Pattern API_ARTIFACT = Pattern.compile("\"name\"\\s*:\\s*\"(kraken-api-[^\"]+\\.jar)\"");

    private KrakenBootstrap() {
    }

    /**
     * Finds the Kraken API the client runs, according to the saved bootstrap.
     *
     * @param krakenDirectory Kraken's data directory, normally {@code ~/.runelite/kraken}
     * @return the API target, read from the cached jar when present and otherwise from its name, or {@code null} when
     * there is no saved bootstrap or it lists no Kraken API
     */
    public static ApiTarget runtimeApi(File krakenDirectory) {
        File repository = new File(krakenDirectory, REPOSITORY_DIRECTORY);
        File bootstrap = new File(repository, BOOTSTRAP_FILE);
        if (!bootstrap.isFile()) {
            return null;
        }

        String json;
        try {
            json = Files.readString(bootstrap.toPath());
        } catch (IOException e) {
            return null;
        }
        Matcher matcher = API_ARTIFACT.matcher(json);
        if (!matcher.find()) {
            return null;
        }

        File jar = new File(repository, matcher.group(1));
        if (jar.isFile()) {
            return ApiTarget.fromJar(jar);
        }
        return new ApiTarget(ApiTarget.versionFromFileName(jar.getName()), null, null);
    }
}
