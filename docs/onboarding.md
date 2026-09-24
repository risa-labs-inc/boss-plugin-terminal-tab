# BOSS Term onboarding

## Architecture

Terminal Tab owns the BOSS setup UI, task planning, verification, retry state, and setup terminal. It uses the released BOSS Term `1.2.155` terminal APIs and integrates with the released Fluck Agent `1.0.110`; it does not depend on unreleased onboarding code from either project.

BossConsole is the window owner. Help, Toolbox, first-run, and terminal-menu requests all route to one window-scoped host composition. That host handler first shipped in BossConsole `9.5.20`, so the manifest's `minBossVersion` is `9.5.20`: an older host would install this plugin and then show no setup at all, since the plugin no longer renders its own wizard. Hiding or backgrounding the setup window keeps that composition and its `TabbedTerminal` mounted. This is required because BOSS Term `1.2.155` closes the PTY when its renderer unmounts. The status-bar item brings the same session back to the foreground.

The setup controller creates a dedicated terminal tab and waits for a command-execution readiness marker before sending installation commands. **Debug with Fluck** sends that terminal ID, a request ID, the owning window ID, the active task, and recent terminal output through a validated BossConsole compatibility bridge. BossConsole opens the Fluck panel only after accepting the request. The setup window then backgrounds while its terminal remains alive.

Fluck `1.0.110` cannot signal that a turn has finished. The user chooses **Resume and verify** when the proposed changes are ready. That action invalidates the handoff token and runs the task's verifier before setup resumes. The setup terminal stays out of the generic MCP registry; Fluck can reach it only through the dedicated setup tools while their exact request token remains valid. Availability means that interactive debugging can be requested; it does not mean Fluck automatically watches, repairs, completes, or verifies setup. Setup authenticates `sudo` up front, so before Fluck is handed the terminal the controller runs `sudo -K` there: every cached credential is dropped, and any administrator command Fluck runs stops at a password prompt the user answers. If that cannot be confirmed, the handoff is refused.

Host requests carry a window ID, and the host opens Fluck in that window. Released Fluck's review inbox is process-wide: another open Fluck panel can consume the prompt, and a busy Fluck turn can delay it. The prompt requires validation of the exact terminal and request IDs before acting; guarded setup tools reject a resumed or expired handoff. One setup controller and one handoff can be active at a time. If the terminal is busy, the controller first interrupts it and waits for a safe command boundary; if that boundary cannot be confirmed, setup shows an actionable message.

Setup authenticates `sudo` up front, so before Fluck gets the terminal on macOS and Linux the controller runs `sudo -K` to drop every cached credential for the user. An administrator command Fluck runs then stops at a password prompt the user answers in the terminal. If the credentials cannot be dropped, the handoff is refused.

Terminal Tab owns setup choices, task planning, and completion state. Released BossTerm supplies installed-tool detection and GitHub authentication UI; new detected tools must be explicitly mapped into the plugin-owned setup model.

## Build and tests

From `boss-plugins/terminal-tab`:

```shell
./gradlew test buildPluginJar
```

`test` runs the controller, install-plan, status, and routing tests. `buildPluginJar` creates the installable bundled JAR; its final guard checks that BOSS Term, PTY4J, and JNA runtime classes are present. The ordinary `jar` task intentionally produces a thin diagnostic artifact under `build/diagnostic-libs`, outside the release upload glob.

## Quick manual check

1. Open setup from Help, choose options, and start it. Confirm commands and any `sudo` prompt appear in the embedded terminal.
2. Continue in the background, switch or close the originating terminal tab, then reopen setup from the status bar. Confirm the same output and terminal session remain.
3. Open setup again from Help and Toolbox. Confirm they foreground the existing setup instead of creating another terminal.
4. During a running or failed step, choose **Debug with Fluck**. Confirm the Fluck panel opens and the setup window backgrounds only after acceptance. On macOS or Linux, confirm that a `sudo` command sent during the handoff asks for a password.
5. Return through the setup status item and choose **Resume and verify**. Confirm terminal access is revoked, verification runs, and setup continues only when verification passes.
6. Exercise success, failed retry, and optional GitHub sign-in/skip. Confirm only a completed setup reaches the success screen.

The shared release workflow increments the Gradle version by a patch before publishing; this PR does not manually pre-bump it.
