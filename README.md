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

- A BOSS Console host shipping Plugin API 1.0.97 or later
  (`minBossVersion` requires BOSS Console 9.5.34 or later).

## Installation

1. Download the latest JAR from the [Releases](https://github.com/risa-labs-inc/boss-plugin-terminal-tab/releases) page
2. Open BOSS Console
3. Go to Settings > Plugins > Install from File
4. Select the downloaded JAR file

Or install via Plugin Store in BOSS Console.

## Building

```bash
./gradlew buildPluginJar
```

The plugin JAR will be created in `build/libs/`.

## API compatibility gate

The manifest requires Plugin API 1.0.97. Release CI, test CI, and the local
compile/test classpaths pin that same version; `PluginManifestTest` checks the
processed manifest against all three pins. Update them together when adopting
new host API symbols: change `bossPluginApiVersion` in `build.gradle.kts`,
`boss_plugin_api_version` in `.github/workflows/build.yml`, `API_VERSION` in
`.github/workflows/test.yml`, and both API fields in `plugin.json`. We deliberately compile against the
minimum supported API, so using a newer symbol requires an explicit gate update.

This gate protects hosts that report their installed API version. Hosts with an
unknown API version fail open in both the updater and loader, so the manifest
alone does not protect those hosts. `minBossVersion` is 9.5.34 because terminal-link
destination selection requires that host implementation.

Publishing a manifest does not repair existing store records. Verify the API
gate on previously published versions, including any version used as a fallback.
Store data must be verified separately from this repository's build.

## Shared rendering runtime

The host owns Compose, Skia and Skiko. `buildPluginJar` rejects bundled
Compose/Skia/Skiko classes and Skiko native libraries to prevent duplicate runtime
ownership. BossTerm's font and image code uses shared Compose APIs, so the plugin
does not need local rendering overrides.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

Copyright 2025-2026 Risa Labs Inc.

## BossTerm rendering and dependency updates

The bundled BossTerm includes the font-selection and inline-image compatibility
fixes that previously required plugin copies of `FontUtils` and `ImageRenderer`.
Those copies and their packaging exclusions have been removed. Fonts are discovered
with AWT and resolved through Compose's `SystemFont`; inline images decode through
`androidx.compose.ui.res.loadImageBitmap`. The host owns the rendering runtime.

`CurrentHostFontUtilsTest` and `CurrentHostImageRendererTest` load the upstream
classes from the installable plugin JAR with direct Skia/Skiko access blocked.
They verify font selection and fallback, image decoding, caching and invalid-image
handling. The packaging check continues to reject a second rendering runtime.
Upstream also routes MCP WebP conversion and macOS SF Symbol decoding through
shared Compose. Standalone window rendering remains outside the plugin's lifecycle.

The source-override version guard is no longer needed, so the automatic dependency
updater can resume tracking BossTerm releases on Maven Central.
It runs the full Linux build and tests before automatically merging a dependency
bump; the release workflow repeats validation before publishing. Failed validation
leaves a draft PR for review, with a link to the workflow logs. An existing bump PR
prevents repeated attempts for the same BossTerm version.
Validation runs in a separate job with read-only repository permissions and no
saved checkout credentials; a fresh job regenerates the bump to open/merge the PR.
Failed drafts require a maintainer to push a fix or retry commit with their own
credentials (triggering PR CI), mark it ready and merge it. Closing a draft alone
does not retry that version, and bot-created PRs do not trigger PR CI themselves.

The new `list_machines` MCP tool groups tabs already registered in this BOSS process:
the local machine and any remote shares currently joined here. It does not fetch an
account's machine directory or read standalone BossTerm credentials. Account sharing
continues to use the host identity installed by `HostAccountSessionBridge`.

BossTerm's shared session engine is included in this bundle. BOSS terminal tabs
still use the embedded session lifecycle; upgrading the library does not attach
them to the standalone BossTerm daemon or keep them alive after BOSS exits.
