# BOSS Term onboarding

## Architecture

Terminal Tab owns the BOSS setup UI, task planning, verification, retry state, and setup terminal. It uses the released BOSS Term `1.2.155` terminal APIs and integrates with the released Fluck Agent `1.0.110`; it does not depend on unreleased onboarding code from either project.

BossConsole is the window owner. Help, Toolbox, first-run, and terminal-menu requests all route to one window-scoped host composition. Hiding or backgrounding the setup window keeps that composition and its `TabbedTerminal` mounted. This is required because BOSS Term `1.2.155` closes the PTY when its renderer unmounts. The status-bar item brings the same session back to the foreground.

The setup controller registers the terminal container and adopts its real tab UUID. **Debug with Fluck** sends that terminal ID, a request ID, the owning window ID, the active task, and redacted recent output through a validated BossConsole compatibility bridge. BossConsole opens the Fluck panel only after accepting the request. The setup window then backgrounds while its terminal remains alive.

Fluck `1.0.110` cannot signal that a turn has finished. The user chooses **Resume and verify** when the proposed changes are ready. That action invalidates the handoff token, removes the setup terminal from the generic MCP registry, and runs the task's verifier before setup resumes. A generic call already dispatched before removal cannot be cancelled through the released API. Availability means that interactive debugging can be requested; it does not mean Fluck automatically watches, repairs, completes, or verifies setup.

Host requests carry a window ID, and the host opens Fluck in that window. Released Fluck's review inbox is process-wide: another open Fluck panel can consume the prompt, and a busy Fluck turn can delay it. The prompt requires validation of the exact terminal and request IDs before acting; guarded setup tools reject a resumed or expired handoff. One setup controller and one handoff can be active at a time. If the terminal is busy, the controller first interrupts it and waits for a safe command boundary; if that boundary cannot be confirmed, setup shows an actionable message.

## Build and tests

From `boss-plugins/terminal-tab`:

```shell
./gradlew test buildPluginJar
```

`test` runs the controller, install-plan, status, and routing tests. `buildPluginJar` creates the installable bundled JAR; its final guard checks that BOSS Term, PTY4J, and JNA runtime classes are present. The ordinary `jar` task intentionally produces a thin diagnostic artifact.

## Quick manual check

1. Open setup from Help, choose options, and start it. Confirm commands and any `sudo` prompt appear in the embedded terminal.
2. Continue in the background, switch or close the originating terminal tab, then reopen setup from the status bar. Confirm the same output and terminal session remain.
3. Open setup again from Help and Toolbox. Confirm they foreground the existing setup instead of creating another terminal.
4. During a running or failed step, choose **Debug with Fluck**. Confirm the Fluck panel opens and the setup window backgrounds only after acceptance.
5. Return through the setup status item and choose **Resume and verify**. Confirm terminal access is revoked, verification runs, and setup continues only when verification passes.
6. Exercise success, failed retry, and optional GitHub sign-in/skip. Confirm only a completed setup reaches the success screen.
