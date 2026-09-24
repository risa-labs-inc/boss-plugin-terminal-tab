# AGENTS.md

## Project Overview

**Terminal Tab** (`ai.rever.boss.plugin.dynamic.terminaltab`) is a dynamic plugin for the BOSS desktop application.

Terminal tab using BossTerm library for terminal emulation

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.terminaltab`
- **Main Class**: `ai.rever.boss.plugin.dynamic.terminaltab.TerminalTabDynamicPlugin`
- **API Version**: 1.0.89 (status-bar setup progress and reopen support)

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

### `run_in_sidebar` takes an `env` object

Environment variables for the command, as NAME to value. The values never go on the command line:
`SidebarEnvInjection` writes them to an owner-only file under `~/.boss/run/env/<pid>/` (the file
0600 from creation, the directories 0700), the shell loads and deletes it, and only then runs the
command. The scrollback and the runner entry carry the file's path, the tool's result the caller's
command and the variable NAMES; never a value.

- POSIX shells (bash, zsh, fish): `. '<file>' && rm -f '<file>' && eval '<command>'`. `eval` keeps a
  compound command (`a; b`) wholly behind the `&&`.
- Windows PowerShell: the file is data (`NAME=<base64 of the value>`), read with `Get-Content`, not
  a dot-sourced script. So the execution policy cannot block it (`Restricted` is the default on
  Windows client editions), and no value is ever parsed, so no quote character, including the
  curly quotes PowerShell also treats as quotes, can break out. The command runs only when loading
  succeeded (`if ($__bossEnvOk) { ... }`; Windows PowerShell 5.1 has no `&&`).

Either way, a command whose file cannot be loaded prints why and does not run. That covers a re-run
from the top-bar runner, whose entry holds the wrapped command and whose file is gone after the
first run: run it again through the agent.

A value may be a `{{secret:<id>}}` reference, which the host resolves after the operator approves;
that is how a credential reaches a shell command without the agent ever holding it. That needs a
BossConsole with #822 (merged after 9.5.23); on an older host the literal `{{secret:<id>}}` string
reaches the shell.

No path leaves a file behind: it is deleted when the sidebar start throws or queues nothing, each
new write removes this process's env files older than 10 minutes (a command swallowed by a busy
terminal), and plugin start and stop remove this process's files and those of any process that is
no longer running. A live other BOSS process sharing the data root (a second launch, a restart
while the old JVM exits) keeps its own directory, so a sweep never takes a file its shell still
needs. `SidebarEnvInjectionTest` runs the wrapper under every shell present, PowerShell included
under the `Restricted` policy (Windows PowerShell 5.1 on the Windows CI job, pwsh on Linux).

Not a secure enclave: the shell and every process it starts have the value, and `printenv`
prints it. The clean long-term shape is an `environment` parameter on BossTerm's tab creation,
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
