package com.krakenplugins.gradle;

import com.krakenplugins.gradle.internal.RuntimeApi;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Finds the Kraken API the client runs once per build, shared by every {@code krakenCheck} and {@code krakenRelease}.
 * Tasks of different projects run in parallel, so this also keeps them from fetching the bootstrap and writing the same
 * cached jar at the same time.
 */
public abstract class RuntimeApiService implements BuildService<RuntimeApiService.Params> {

    static final String NAME = "krakenRuntimeApi";

    /**
     * The service's parameters.
     */
    public interface Params extends BuildServiceParameters {

        /**
         * @return where verified Kraken API jars are kept between builds
         */
        DirectoryProperty getCacheDirectory();
    }

    private final Map<String, Result> results = new HashMap<>();

    /**
     * Resolves the runtime API, or returns this build's earlier result for the same inputs. See
     * {@link RuntimeApi#resolve}.
     *
     * @param bootstrapUrl    the bootstrap's URL
     * @param krakenDirectory Kraken's data directory, holding the launcher's saved bootstrap
     * @param offline         whether Gradle runs with {@code --offline}
     * @param warn            receives a warning the first time the saved bootstrap is used instead
     * @return the runtime API, or {@code null} when offline with no saved bootstrap
     * @throws IOException when the bootstrap can't be fetched and there is no saved bootstrap
     */
    public synchronized RuntimeApi resolve(String bootstrapUrl, File krakenDirectory, boolean offline, Consumer<String> warn)
            throws IOException {
        String key = bootstrapUrl + "|" + krakenDirectory + "|" + offline;
        Result result = results.get(key);
        if (result == null) {
            try {
                result = new Result(RuntimeApi.resolve(bootstrapUrl, getParameters().getCacheDirectory().get().getAsFile(),
                        krakenDirectory, offline, warn), null);
            } catch (IOException e) {
                result = new Result(null, e);
            }
            results.put(key, result);
        }
        if (result.failure != null) {
            throw new IOException(result.failure.getMessage(), result.failure);
        }
        return result.api;
    }

    private static final class Result {

        private final RuntimeApi api;
        private final IOException failure;

        private Result(RuntimeApi api, IOException failure) {
            this.api = api;
            this.failure = failure;
        }
    }
}
