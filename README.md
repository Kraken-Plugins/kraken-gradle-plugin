# Kraken Gradle Plugin

Gradle plugin for developing Kraken plugins. It launches the installed Kraken client with the plugin you're working
on sideloaded, and checks that the Kraken API you compile against works with the one the client runs.

## Usage

```groovy
// settings.gradle
pluginManagement {
    repositories {
        maven { url 'https://repo.kraken-plugins.com' }
        gradlePluginPortal()
    }
}
```

```groovy
// build.gradle
plugins {
    id 'com.krakenplugins.plugin' version '0.1.0'
}

kraken {
    // All optional.
    // profile = 'MyCharacter'                         // linked Jagex profile, passed as --kraken-profile
    // clientArguments = ['--developer-mode', '--debug']
    // sideload.from(project(':other-plugin').tasks.named('jar'))
    // runeliteDirectory = file('/path/to/RuneLite')   // required on Linux
}
```

The plugin applies `java`. Kraken API and RuneLite dependencies are still declared as usual (`compileOnly`).

## Tasks

| Task | What it does |
|---|---|
| `runKraken` | Builds the plugin jar and launches the Kraken client with it sideloaded. `--debug-jvm` waits for a debugger on port 5005; `--profile <name>` logs in as a linked Jagex profile. |
| `krakenVersions` | Prints the Kraken API and RuneLite the project compiles against and the Kraken API the client runs, warning about combinations that fail at runtime. |

### How `runKraken` launches the client

It starts RuneLite's own launcher from the RuneLite installation (`%LOCALAPPDATA%\RuneLite` on Windows,
`/Applications/RuneLite.app/Contents/Resources` on macOS) with `KrakenSetup.jar` attached as a Java agent: the same
command the Kraken launcher's installer configures. That means:

- **The client runs its own Kraken API.** The agent downloads the signed Kraken bootstrap (or uses the saved copy
  when offline), so the client runs the Kraken API and Kraken-Client built for the current RuneLite. The API version
  you compile against only matters at compile time.
- **Your jar is sideloaded without being copied.** The jar's path is passed to Kraken-Client as
  `-Dkraken.sideload.paths`, which needs Kraken-Client 1.18.0 or newer. Jars in `~/.runelite/kraken/sideloaded-plugins`
  still load as well; the `runKraken` jars take precedence.
- RuneLite's bundled JRE is used when present, otherwise a Java 17 toolchain.

### Version warnings

Kraken API releases stay backwards compatible, so compiling against an older API than the client runs is fine. The
plugin warns when:

- the API you compile against is **newer** than the one the client runs, since newer methods throw
  `NoSuchMethodError` in the client;
- the API's hooks target a **different RuneLite** than the project compiles against. This only matters for IDE or
  test runs that put `kraken-api` on the classpath; `runKraken` is unaffected.

The hook target comes from the `Kraken-RuneLite-Version` and `Kraken-Game-Revision` attributes in the Kraken API jar's
manifest, which `kraken-api` copies from the `runeliteVersion` and `revision` fields `kraken-updater` writes into
`hooks.json`.

## Development

```shell
./gradlew build      # compile, validate the plugin and run unit + TestKit tests
```

The release workflow publishes with `./gradlew publishAllPublicationsToStagingRepository` and uploads
`build/maven-repo` to `kraken-bootstrap-static/maven/`, like the Kraken API.
