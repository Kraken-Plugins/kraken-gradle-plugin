# Kraken Gradle Plugin

Gradle plugin for developing Kraken plugins. It sets up the Kraken API and RuneLite dependencies, launches the installed
Kraken client with the plugin you're working on sideloaded, checks the plugin against the Kraken API the client runs, and
builds signed releases that the client can load as an external repository.

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
    id 'com.krakenplugins.plugin' version '0.2.0'
}

kraken {
    // All optional.
    // apiVersion = '5.2.2'                            // adds com.github.kraken:kraken-api (compileOnly, testImplementation)
    // runeliteVersion = '1.13.1'                      // adds net.runelite:client (compileOnly, testImplementation)
    // lombokVersion = '1.18.30'                       // adds Lombok and its annotation processor
    // addRepositories = false                         // when settings.gradle declares the repositories
    // profile = 'MyCharacter'                         // linked Jagex profile, passed as --kraken-profile
    // clientArguments = ['--developer-mode', '--debug']
    // sideload.from(project(':other-plugin').tasks.named('jar'))
    // runeliteDirectory = file('/path/to/RuneLite')   // required on Linux

    // release {
    //     name = 'My Plugins'                         // defaults to the project name
    //     version = '1.0.0'                           // defaults to the project version
    //     author = 'me'
    //     description = 'What these plugins do'
    //     github = 'me/my-plugins'                    // jars are downloaded from this repository's GitHub releases
    //     artifactBaseUrl = 'https://example.com/plugins/' // instead of github
    //     jars.setFrom(...)                           // defaults to every subproject's jar, or this project's
    //     pluginNames.put('mining', 'Power Miner')    // manifest name for mining-<version>.jar
    // }
}
```

With `apiVersion` and `runeliteVersion` set, the plugin adds the Kraken, RuneLite and Maven Central repositories, each
limited to the groups it serves, and declares both dependencies `compileOnly` (the client provides them at runtime) and
`testImplementation`. Without them, declare the dependencies yourself as before.

## Tasks

| Task | What it does |
|---|---|
| `runKraken` | Builds the plugin jar and launches the Kraken client with it sideloaded. `--debug-jvm` waits for a debugger on port 5005; `--profile <name>` logs in as a linked Jagex profile. |
| `krakenVersions` | Prints the Kraken API and RuneLite the project compiles against and the Kraken API the client runs, warning about combinations that fail at runtime. |
| `krakenCheck` | Checks the plugin jar against the Kraken API the client runs. Part of `check`, so `build` runs it. |
| `krakenManifest` | Writes the unsigned external repository manifest to `build/kraken/manifest.json`. |
| `krakenRelease` | Checks every plugin jar, then writes the jars and a signed `manifest.json` to `build/kraken-release`. |
| `krakenKeygen` | Creates an Ed25519 signing key, by default in `~/.kraken/keys/<project>.pem`. `--output <file>` writes it elsewhere. |

`krakenManifest`, `krakenRelease` and `krakenKeygen` belong to the root project when it applies the plugin, and release
the jars of every subproject that applies it. In a build whose root doesn't apply the plugin, each project has its own.

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

Compiling against an older Kraken API than the client runs is usually fine, but not always: 5.1.4 and 5.2 changed the
signatures of methods earlier releases had (`GroundObjectQuery.stackValueAbove`, `ItemPriceService.getItemPrice`). `krakenCheck` (below) catches both directions. `krakenVersions` warns
when:

- the API you compile against is **newer** than the one the client runs, since newer methods throw
  `NoSuchMethodError` in the client;
- the API's hooks target a **different RuneLite** than the project compiles against. This only matters for IDE or
  test runs that put `kraken-api` on the classpath; `runKraken` is unaffected.

The hook target comes from the `Kraken-RuneLite-Version` and `Kraken-Game-Revision` attributes in the Kraken API jar's
manifest, which `kraken-api` copies from the `runeliteVersion` and `revision` fields `kraken-updater` writes into
`hooks.json`.

### Checking against the client's Kraken API

`krakenCheck` reads the plugin's compiled classes and looks up every Kraken API class, field and method they use in the
Kraken API jar the client runs, following super classes and interfaces the way the JVM does. Anything missing fails the
build with the class that uses it:

```
mining-1.0.0.jar won't work in the Kraken client, which runs Kraken API 5.1.2 (from https://seaweed.kraken-plugins.com/...):
  - com.example.LootTask calls com.kraken.api.query.groundobject.GroundObjectQuery.stackValueAbove(long), which Kraken API 5.1.2 doesn't have.
```

It also fails when the jar has no `@PluginDescriptor` class or targets a newer Java than 17, and warns when the jar bundles
RuneLite or Kraken API classes, which the client ignores in favour of its own.

The API it checks against is the one the production Kraken bootstrap lists (`kraken.bootstrapUrl`), which is what users'
launchers download. The jar is verified against the bootstrap's SHA-256 and cached in
`~/.gradle/caches/kraken-plugin/api`. When the bootstrap can't be fetched, or Gradle runs with `--offline`, the check uses
the Kraken launcher's saved bootstrap instead, and is skipped with a warning when there is none. `krakenRelease` never
skips it.

### Releasing an external repository

The Kraken client can load plugins from any URL that serves a signed manifest (see the external repositories guide). A
release is three steps:

1. Create a signing key once with `./gradlew krakenKeygen`, and store the file it writes as the `KRAKEN_SIGNING_KEY`
   secret in CI. Users pin this key when they first add the repository, and updates signed with it apply without a
   review, so keep a backup and never replace it.
2. Run `./gradlew krakenRelease` with `KRAKEN_SIGNING_KEY` (the PEM text) or `KRAKEN_SIGNING_KEY_FILE` (its path) set. It
   fails rather than write an unsigned manifest.
3. Publish `build/kraken-release`: with `release.github` set, attach its files to the GitHub release tagged
   `v<version>` (`release.tag` changes the tag). The key's fingerprint is in `build/kraken/signing-key-fingerprint.txt`
   for the release notes.

Each plugin is listed under a name derived from its jar, so `auto-mining-1.2.0.jar` becomes `Auto Mining`, version
`1.2.0`. The client remembers which plugins a user approved by these names, so keep jar names stable, or pin a name with
`release.pluginNames`. Keys from `openssl genpkey -algorithm ed25519` work as well. Signing needs Gradle to run on Java
15 or newer.

## Development

```shell
./gradlew build      # compile, validate the plugin and run unit + TestKit tests
```

Every push to `master` releases the plugin through `.github/workflows/release.yml`, the same way as the Kraken API.
The first release of a `version.txt` version uses it as-is; later pushes release the next patch of that major.minor
(0.1.1, 0.1.2, ...). The workflow publishes with `./gradlew publishAllPublicationsToStagingRepository`, uploads
`build/maven-repo` to `kraken-bootstrap-static/maven/` (served as https://repo.kraken-plugins.com), then tags the
version and creates a GitHub release. It needs the `SEAWEED_ACCESS_KEY`, `SEAWEED_SECRET_KEY` and `SEAWEED_URL`
secrets (`SEAWEED_URL` includes the scheme, e.g. `https://seaweed.kraken-plugins.com`).
