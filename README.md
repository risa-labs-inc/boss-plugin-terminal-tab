# BOSS Terminal Tab Plugin

A dynamic plugin that provides terminal tabs in the main panel area of BOSS Console.

## Features

- Persistent terminal sessions (survive tab switching)
- Full BossTerm integration with syntax highlighting
- Working directory support
- Initial command execution
- Tab title updates via escape sequences (OSC 0/1/2)
- Split pane support within terminal tabs

## Requirements

- A BOSS Console host shipping Plugin API 1.0.89 or later
  (`minBossVersion` requires BOSS Console 9.5.26 or later).

## Installation

1. Download the latest JAR from the [Releases](https://github.com/risa-labs-inc/boss-plugin-terminal-tab/releases) page
2. Open BOSS Console
3. Go to Settings > Plugins > Install from File
4. Select the downloaded JAR file

Or install via Plugin Store in BOSS Console.

## Building

```bash
./gradlew jar
```

The plugin JAR will be created in `build/libs/`.

## API compatibility gate

The manifest requires Plugin API 1.0.89. Release CI, test CI, and the local
compile/test classpaths pin that same version; `PluginManifestTest` checks the
processed manifest against all three pins. Update them together when adopting
new host API symbols: change `bossPluginApiVersion` in `build.gradle.kts`,
`boss_plugin_api_version` in `.github/workflows/build.yml`, `API_VERSION` in
`.github/workflows/test.yml`, and both API fields in `plugin.json`. We deliberately compile against the
minimum supported API, so using a newer symbol requires an explicit gate update.

This gate protects hosts that report their installed API version. Hosts with an
unknown API version fail open in both the updater and loader, so the manifest
alone does not protect those hosts. `minBossVersion` is now 9.5.26 for the shared rendering runtime.

Publishing a manifest does not repair existing store records. Verify the API
gate on previously published versions, including any version used as a fallback.
Store data must be verified separately from this repository's build.

## Shared rendering runtime

BossConsole 9.5.26 or later must provide Compose, Skia and Skiko through its shared
classloader. BossTerm decodes inline images into Skia objects consumed by host Compose;
these classes and their native runtime must have one owner. `buildPluginJar` rejects
bundled Compose/Skia/Skiko classes and Skiko native libraries.

Release the host fix before this plugin. BossConsole 9.5.25 restricts host-class access
without sharing Skia/Skiko, so inline image rendering can disable the terminal plugin.
The minimum host version prevents offering this release to that affected host.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

Copyright 2025-2026 Risa Labs Inc.
