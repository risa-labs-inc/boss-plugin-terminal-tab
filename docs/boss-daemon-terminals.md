# BOSS background terminals

With daemon mode enabled and a supporting host, main, sidebar and embedded terminal
surfaces attach to the terminal service in BOSS's shared daemon. They do not launch the
standalone BossTerm daemon. Explicitly disabled daemon mode retains local terminals;
connection failures show Retry rather than creating a second shell. Mode is selected for a retained
terminal lifetime: changing daemon mode takes effect after terminal reset or plugin reload.

Window closure/plugin UI reload detach mirrors. Reopening a workspace reclaims its first
available window slot while live sibling windows keep separate terminal surfaces. With several
windows on the same workspace, slots follow first-terminal composition order; after all windows
close, a reopened window may reclaim a different former window's surface. Workspace switching
keeps an existing terminal's original identity; reset to create a terminal for the new workspace.
Explicit
terminal removal/reset queues that surface's daemon PTY close even if connection is still
pending. Transient close failures retry automatically (three attempts), including anonymous
terminals. Failed intents remain pending; a replacement or unload retry attempts them again.
Unload uses a five-second connection-job drain while processing UI events. An incomplete drain
or unresolved close defers unloading and retains the active classloader; it never reports
success and drops the pending close. Local opt-out never issues daemon close requests. A new worker restores empty
surfaces after reboot; it does not resume an interrupted OS process.

The host keeps MCP, account identity and voice credentials. Hosted attach traffic cannot
turn off the host's MCP indicator. Existing account-managed shares and calls remain
UI-owned; their current credential and account-switch behavior is preserved. Manual daemon
shares use the pool's background sharing server, so their terminal transport survives UI
closure. Share All Windows spans every daemon surface. Moving authenticated account
presence/refresh and host credential-dependent remote calls into the background requires a
host-owned background credential broker; this change does not claim those services survive
BOSS exit or implement Fluck's web migration.

Required releases are API 1.0.99, a BOSS host containing the provider (target 9.5.44), and
BossTerm 1.2.183. These are dependency targets, not a claim that they are already published.
Verify the actual versions before releasing this plugin. Development validation uses
`-PuseLocalBossTerm=true -PbossTermDevelopmentVersion=1.2.183-SNAPSHOT` with a locally built
API JAR. Do not install this plugin into an older running host.

Manual checks: real command output after window close/reopen; multiple windows restoring the
same workspace in a different order; workspace switching; main/sidebar isolation;
runner and MCP programmatic tabs; splits and close/reset; share link and approval after
UI detach; local-mode opt-out; Retry without duplicate initial commands; plugin reload;
profile separation; and the B-square daemon icon activating the existing BOSS process.
