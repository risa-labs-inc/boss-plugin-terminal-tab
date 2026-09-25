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
  (`minBossVersion` requires BOSS Console 9.5.20 or later).

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
alone does not protect those hosts. `minBossVersion` remains 9.5.20 so affected 9.5.25 hosts can receive the image hotfix.

Publishing a manifest does not repair existing store records. Verify the API
gate on previously published versions, including any version used as a fallback.
Store data must be verified separately from this repository's build.

## Shared rendering runtime

The host owns Compose, Skia and Skiko. `buildPluginJar` rejects bundled
Compose/Skia/Skiko classes and Skiko native libraries to prevent duplicate runtime
ownership. The image-decoding compatibility path below works before the general
host sharing fix, so this plugin release can ship independently of BossConsole 9.5.26.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

Copyright 2025-2026 Risa Labs Inc.

## BOSS 9.5.25 inline-image compatibility

This release keeps the existing minimum host version and carries a temporary source
override of BossTerm 1.2.167's `ImageRenderer`. It decodes encoded images using
`androidx.compose.ui.res.loadImageBitmap`, so decoding happens inside the host's
already-shared Compose runtime. The host owns Skia and the returned ImageBitmap;
the plugin does not bundle Skia/Skiko or change the host's classloader policy.

The override preserves BossTerm's renderer API, cache and placement behavior. Its
upstream class is explicitly excluded from the fat JAR. The build requires reviewing
or removing this override when upgrading BossTerm; remove it once the upstream
renderer uses the shared Compose API. The public Compose API is deprecated but remains
available on the affected host, unlike the recommended resources package which is not
part of that host's shared surface.

`CurrentHostImageRendererTest` loads the renderer from the shipped plugin JAR with
direct Skia/Skiko resolution blocked, decodes a PNG through host Compose, and checks
cache behavior and invalid-image handling. This fixes the reported inline-image crash;
the host shared-rendering fix is still needed for other direct Skia/Skiko consumers.
