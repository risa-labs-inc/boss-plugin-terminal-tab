# AGENTS.md

## Project Overview

**Terminal Tab** (`ai.rever.boss.plugin.dynamic.terminaltab`) is a dynamic plugin for the BOSS desktop application.

Terminal tab using BossTerm library for terminal emulation

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.terminaltab`
- **Main Class**: `ai.rever.boss.plugin.dynamic.terminaltab.TerminalTabDynamicPlugin`
- **API Version**: 1.0.97 (terminal-link destination requests; verify the released API version)

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing.

## Architecture

### Plugin Structure
```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.*)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest
build.gradle.kts   → Build config + version (single source of truth)
```

### Key Patterns
- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`
- UI: `PanelComponentWithUI` with `@Composable Content()`
- State: ViewModel pattern with `StateFlow`
- Providers from `PluginContext`: `workspaceDataProvider`, `splitViewOperations`, `contextMenuProvider`, `activeTabsProvider`
- Null-safe provider access: providers may be null, UI must handle gracefully

### Dependencies
- **boss-plugin-api**: compileOnly (provided by host app at runtime)
- **Compose Desktop**: UI framework
- **Decompose**: Navigation and component lifecycle
- **Coroutines**: Async operations

## The two host tools go through the host's MCP tool registry

`run_in_sidebar` and `cli` are defined once, in `McpHostTools.kt` (`bossHostMcpToolDefs`), and
projected three ways: onto the host's `McpToolRegistry` by `HostMcpToolProvider` (the normal
path), straight onto the `boss` MCP server by `bossHostMcpTools` (only when the host has no
registry, so an old host keeps them), and onto the voice surface by `BossVoiceToolSource`.

The registry path is what makes them governed like every other plugin's tools: the per-tool
kill-switch, RBAC, ALLOW/ASK/DENY with the approval dialog, the operation ledger, the host result
cap, and `{{secret:<id>}}` references resolved by the host after approval (BossConsole#495 is the
gap this closes for this plugin's own two tools; BossTerm's built-ins are still served by
BossTerm's own server and are out of scope here). When they are in the registry, the bridge in
`McpDynamicTools` is told not to reserve these two names (only `bossTermOwnToolNames` and the
setup tools below), or it would skip exactly the tools it should carry; `RESERVED_TOOL_NAMES` keeps both sets for the fallback and for the voice
surface, which filters registry tools by it so the host tools appear there once.

The four `setup_terminal_*` tools (Debug with Fluck) are the exception: they stay straight on the
server in every mode (`serverRegisteredHostTools`, wired by `installBossServerTools`), and their
names stay reserved with BossTerm's own. They are reachable only with the exact request token of an
accepted handoff, the registry provider does not carry them, and routing them through it would ask
for approval on every keystroke Fluck sends. `BossServerToolInstallTest` pins which door each tool
uses in each host shape.

The voice path is unchanged: BossTerm's voice executor calls `HostMcpTool.handler` directly under
its own policy and never reaches the registry.

### Reach the host through the plugin API, never by reflection

Since BOSS 9.5.25 (BossConsole `dc443ee9c`) the plugin classloader refuses every host class
outside its shared packages, on purpose: a plugin must not resolve host credentials or process
control by class name. `Class.forName("ai.rever.boss.utils.DeepLinkHandler")` and the like now
throw `ClassNotFoundException`, which silently broke the `cli` tool ("dispatcher unavailable"),
`run_in_sidebar` ("No focused BossConsole window") and clicking a link in a terminal.

`HostWindows` is the way in now:

- a `boss://` link goes to `SplitViewOperations.openUrlInActivePanel`, which the host hands to
  `DeepLinkHandler.processDeepLink` (`routePluginDeepLink`), so every `cli` verb keeps its old
  origin and the host's confirmation where it has one;
- the window an MCP tool acts in is the last one a terminal composable saw focused (the host
  publishes no focus event to plugins), else the most recently registered;
- a terminal link requests its own window's chooser through `SplitViewOperations.openTerminalLink`,
  honoring Always Ask and remembered destinations. File requests retain line and column; providers
  without chooser support keep ordinary URL/file opening. Only `http` and `https` are opened as web
  links; any other
  scheme matched in terminal output (`boss://`, `javascript:`) is left to BossTerm, because
  terminal output can be written by anyone. A file link with no window to open in is swallowed,
  never passed to BossTerm's fallback, which would hand it to the OS opener.
- host calls run on `hostCallContext` (the UI thread). The host's implementations switch threads
  themselves today, but the API does not promise it.

Known loss, because the plugin API has no surface for it: the top-bar runner listing
`run_in_sidebar` runs. It needs a host-implemented API. `ShellUtils`, `RunnerSettingsManager` and `BossDirectories` are still looked
up by reflection and always fall back; their fallbacks are what runs today.

### `run_in_sidebar` takes an `env` object

Environment variables for the command, as NAME to value. Neither the values nor the command go on
the command line: `SidebarEnvInjection` writes both to an owner-only file under
`~/.boss/run/env/<pid>/` (the file 0600 from creation, the directories 0700), and the sidebar is
sent a short, fixed loader line naming that file. The file deletes itself, sets the variables for
the command alone, and runs it. The scrollback and the runner entry carry the loader (a path,
never a value); the tool's result carries the caller's command and the variable NAMES.

Why the command is in the file: a line typed into the terminal before the shell's line editor takes
over is capped at 1024 bytes on macOS, so a long command typed with its wrapper was cut off and
never ran. The loader is a few hundred characters whatever the command.

The file is written in the sidebar shell's own language, detected from BossTerm's default shell
(`$SHELL` on Unix, the PowerShell/cmd setting on Windows):

- bash, zsh, sh: the command runs in a subshell that alone has the variables, so none of them
  outlives it in the sidebar shell (a later command, or an agent's `send_input`, cannot read them
  without a new approval). Trade-off: a `cd` or `export` inside the command does not carry over.
- fish: a `begin ... end` block with `set -lx` (variables local to the block), quoted for fish,
  where `\\` and `\'` are escapes inside single quotes. A `cd` does carry over.
- PowerShell: the file is data (`NAME=<base64>` lines and one `:<base64 of the command>` line), read
  with plain .NET calls, never dot-sourced, so the execution policy cannot block it (`Restricted`
  is the default on Windows client editions) and no value reaches the parser. The command runs with
  `Invoke-Expression`, and a `finally` restores every variable afterwards. The loader runs in the
  session's global scope, so an outer `finally` also removes its own working variables (they
  hold the decoded values), which would otherwise be readable by a later `send_input`.
- Any other shell (cmd.exe, nushell, csh): `env` is refused with a clear error and nothing is
  written or run. A command without `env` runs in any shell as before.

A loader whose file cannot be loaded prints why and runs nothing. That covers a re-run from the
top-bar runner, whose entry holds the loader and whose file is gone after the first run: run it
again through the agent. The command's own exit status comes through. The result is `isError` when
nothing was started.

A value may be a `{{secret:<id>}}` reference, which the host resolves after the operator approves;
that is how a credential reaches a shell command without the agent ever holding it. That needs the
call to come through the governed registry on a BossConsole with #822 (merged after 9.5.23). A
reference that arrives unresolved (the voice surface, a host without the registry, or an older
host) is refused, naming the keys, rather than sent to the shell as literal text.

**Secret references belong in `env` values only.** BossConsole's pre-pass (#822) resolves a
reference in every string argument of a governed call, not just `env`, and once substituted a
value cannot be told apart from ordinary text here. Host result scrubbing removes it from this
call's result, but a reference in `command` of a call without `env` is typed into the terminal and
stays in the scrollback in plain text, where a later `read_scrollback` (a separate call the
scrubber does not cover) returns it. With `env`, the command lives in the file and only the loader
is typed. The tool's description tells agents this. The real fix is host-side: a tool declaring
which arguments may carry references (for `run_in_sidebar`, only `env`) and the host refusing them
elsewhere. That needs a boss-plugin-api and BossConsole change and is a follow-up.

Also refused before anything is written: keys that are not variable names, and keys that differ
only in case (`Path` and `PATH` are one variable on Windows). Names that change how every program
loads code or finds executables (`PATH`, `LD_PRELOAD`, `DYLD_*`, `BASH_ENV`, `NODE_OPTIONS` and so
on) are allowed, since the operator approved the call, but listed as `sensitiveEnvKeys` in the
result. Errors name keys and exception types, never values or exception messages.

No path leaves a file behind: it is deleted when the sidebar start throws or queues nothing, each
new write removes this process's env files older than 10 minutes (a command swallowed by a busy
terminal), and plugin start and stop remove this process's files, those of any process that is no
longer running, and stale ones of a live pid (a dead BOSS whose pid was reused). A live other BOSS
process sharing the data root keeps its fresh files, so a sweep never takes one its shell still
needs. `SidebarEnvInjectionTest` runs the real loader under every shell present; CI installs fish
and zsh on Linux and fails if any supported shell is missing there.

Not a secure enclave: the command and every process it starts have the value, and `printenv`
inside it prints it. The clean long-term shape is an `environment` parameter on BossTerm's tab creation,
which builds the PTY environment at spawn; that needs a BossTerm change and is the follow-up.

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json` at build time. Never manually edit the version in `plugin.json` - only change it in `build.gradle.kts`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully - show fallback UI, never crash

## CI/CD

Pushes to `main` trigger the release workflow which:
1. Builds the plugin JAR
2. Creates a GitHub release
3. Publishes to the BOSS Plugin Store

The workflow is defined in `.github/workflows/build.yml` and delegates to the shared workflow in `risa-labs-inc/BossConsole-Releases`.

## Host account sharing

BossConsole owns terminal login. Use host identity and authenticated RPCs; never restore
BossTerm standalone credentials in the plugin. Before adopting a different user, publish
SignedOut, synchronously reset account viewing preferences, stop auto-sharing, revoke share
links, clear discovery, and disconnect account
viewers. Attempt every cleanup step even if one fails; keep sharing signed out on failure
with bounded retries. After exhaustion, keep the bridge disabled until reload and shut
down sharing/account services on IO. Preserve coroutine cancellation.
Account setup and teardown errors must not prevent local terminals or MCP cleanup.
Publisher stop waits for row deletion with a timeout, before closing the host bridge.

Relay tickets and settings handoffs use the host identity through owner-checked RPCs.
Account preference reset must stay synchronous, in-memory and free of blocking I/O.

## Hosted titlebar controls

Titlebar controls require BossConsole 9.5.30. Terminal-link destination requests raise the plugin
floor to the host release containing BossConsole#1798 (expected 9.5.34); verify the actual release
before publishing. macOS hosted windows own Sharing, Call and MCP, including the
call overlay. Other hosts retain the terminal controls. Main and sidebar terminal
focus select Share's target; closing that terminal clears selection without choosing
another session. Moving focus to the titlebar or browser retains the last target.

MCP auto-attachment discovers installed clients and writes the `boss` MCP registration
into their normal user config (for example Claude's `~/.claude.json` and Codex's
`~/.codex/config.toml`), using BossTerm's existing client adapters and protections for
foreign registrations/live sibling instances. Disable automatic installed-client
attachment in MCP settings (`mcpAutoAttachInstalled`); already explicitly attached
clients retain the existing reattach behavior.

For local dependency validation, use `-PuseLocalBossTerm=true` and
`-PbossPluginApiPath=/absolute/path/to/boss-plugin-api`. Maven-local artifacts may
shadow releases: validate the published BossTerm artifact before release. Rendering
compatibility tests must exercise the upstream classes in the installable plugin JAR.
Do not publish a local development artifact to remote repositories.

## Host liquid-glass surfaces

`HostTerminalSurface` reads the enclosing MaterialTheme background alpha before entering BossTerm.
BossConsole supplies a translucent content background only when native glass is installed and its
coverage includes app surfaces. Bridge that into BossTerm's existing LocalNativeWindowGlass and
LocalWindowGlassMode rather than changing SettingsManager or fading the whole terminal. Keep the
wrapper surface transparent in glass mode so the host applies its tint once; opaque hosts and
sidebar-only glass retain the terminal's normal background. The glass surface contracts originated in API 1.0.95 and BossTerm 1.2.173. The plugin now compiles
against API 1.0.97 for terminal-link destination requests. Local testing targets
~/.boss_debug/plugins, not the production directory.

Active host glass also makes `HostTerminalThemeBridge` choose BossTerm’s bundled Liquid Glass
Light/Dark theme before matching background colors, since Liquid Glass shares Blueprint’s floor.
Use the builtin intact for ANSI, selection and chrome colors; host tint still owns transparency.
