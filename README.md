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

- BOSS Console 9.2.20 or later
- Plugin API 1.0.88 or later

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

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

Copyright 2025-2026 Risa Labs Inc.

### API compatibility gate

The manifest requires Plugin API 1.0.88. Release CI, test CI, and the local
compile/test classpaths pin that same version; `PluginManifestTest` checks the
processed manifest against all three pins. Update them together when adopting
new host API symbols.

This gate protects hosts that report their installed API version. Hosts with an
unknown API version fail open in both the updater and loader, so the manifest
alone does not protect those hosts. `minBossVersion` remains 9.2.20.

Publishing this manifest does not repair existing store records. Versions
2.5.72–2.5.74 need their store `min_api_version` backfilled to 1.0.88. Before
relying on 2.5.71 as a fallback, verify its store gate against the API that
release actually requires. Store data and availability of a shipped compatible
host must be verified separately from this repository's build.
