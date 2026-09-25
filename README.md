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

## BOSS 9.5.25 rendering compatibility

This release keeps the existing minimum host version and carries temporary source
overrides of BossTerm 1.2.167's `ImageRenderer` and `FontUtils`. The image renderer decodes images using
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

`FontUtils` discovers and categorizes system fonts using AWT and passes their names
into Compose's `SystemFont`; Compose owns the corresponding rendering typefaces.
Bundled font extraction, the default font, missing-font fallback, and optional emoji
and math families retain their existing behavior. The desktop inventory can differ
from Skia's inventory (for example, Java logical font families may appear).
`CurrentHostFontUtilsTest` loads the shipped override with direct Skia/Skiko access
blocked, enumerates settings fonts and resolves selected, bundled, fallback, and
available emoji/math families through host Compose.

Remaining direct references in bundled BossTerm 1.2.167:

- MCP `show_image` WebP-to-PNG conversion still requires the upstream BossTerm fix
  or the host shared-rendering fix; standard ImageIO-readable images already work.
- macOS SF Symbol decoding in shared tab/status icons falls back to Material icons
  on this host; the upstream library fix restores the native symbols.
- Native title toolbar SVG rendering belongs to standalone BossTerm windows.
  Windows auxiliary glass can encounter blocked Skiko access; BossTerm already
  catches linkage failures and uses opaque surfaces. Neither platform path is
  overridden here; the host shared-rendering fix restores them.

Remove both pinned source overrides when the corrected BossTerm library is released
and the dependency is upgraded. Do not bundle a second Skia/Skiko runtime.
