# Terminal sharing through BossConsole login

The terminal uses `PluginContext.authDataProvider.currentUser` for account identity and
`supabaseDataProvider.rpc` for authenticated operations. BossConsole owns login, refresh,
and logout. No access/refresh token is copied into the plugin or BossTerm's auth file,
and there is no separate terminal sign-in prompt.

Account sharing follows BossTerm's publishing, auto-share, and auto-connect settings.
On sign-out or account switch, the bridge publishes SignedOut, resets account viewing
preferences synchronously, revokes previous share links, clears discovery, and disconnects
account viewers before exposing the new identity. Manually created shares also stop,
because they contain account links. A preference response started before the reset cannot
restore the previous account's settings afterward.

The old account's registry rows may remain visible until their 90-second heartbeat timeout;
their links are already invalid. Explicitly ending a share or unloading while signed in
removes this process's rows through the host. Cleanup attempts every step even if another
step fails; sharing stays signed out and retries cleanup on the next identity event.
Errors log only operation names and exception types, never bearer URLs or RPC payloads.

Missing auth/database providers leave account sharing signed out; local terminals still
work. Plugin disposal stops account services and detaches the host provider. Reloading in
the same classloader uses a stable identity/transport facade, never standalone credentials.

## Account preferences and relay admission

The bridge implements BossTerm's optional `HostTerminalPreferences` and `HostTerminalRelay`
interfaces in addition to `HostAccountSessions`. These three new RPCs use the host's existing
Supabase login:

| RPC | Purpose |
| --- | --- |
| `get_user_terminal_preferences` | Reads the owner's unfocused-pane mode and update FPS. |
| `mint_user_settings_handoff` | Mints a short-lived, one-use handoff to the user-settings Edge Function. |
| `mint_terminal_relay_ticket` | Mints a room-scoped, one-use relay admission ticket. |

Every bridge RPC includes `p_expected_user_id` and checks the authenticated host identity
both before dispatch and after completion. Account changes reject stale responses, including
settings handoffs and relay tickets. Relay room IDs must be canonical UUIDs; supported ticket
roles are `host` (publisher) and `account` (the room owner's other devices). Invalid inputs
are rejected before an RPC, without including those inputs in error messages.

Guest link holders use host approval and the existing end-to-end share handshake; they do
not receive account-role tickets. A relay admission ticket is not a BossConsole access token
and does not itself grant terminal output. The host still authenticates the viewer, approves
its pane scope, and enforces read/control permissions.

The account-settings page uses the one-use handoff to establish a scoped HttpOnly cookie;
changes go through its owner-checked Edge Function RPCs. No host login token enters the page
URL. The library refreshes preferences on login, application focus, and a 60-second poll.
Old backends or temporary failures retain safe defaults or that account's own cached values.

## Backend prerequisites and rollout

1. The existing account-sharing migration
   `20260926000000_terminal_session_host_rpc.sql` was applied to the shared debug backend
   on 2026-09-25. Other backends need it before account registry rollout. Its owner-only
   RPCs reject an expected identity different from `auth.uid()`; existing RLS remains authoritative.
2. Account settings additionally require `20260927000000_user_terminal_preferences.sql`
   and the `user-settings` Edge Function with its configured session secret/public URL.
3. Relay admission additionally requires `20260927010000_terminal_relay_tickets.sql`
   and the Cloudflare Worker/Durable Object under `infra/cloudflare/terminal-relay` in
   BossConsole. Configure its backend service credential only on the server.
   These new prerequisites are separate from the earlier account-sharing migration;
   building this plugin does not deploy them.
4. Release the companion BossTerm change, update this plugin's `bosstermVersion`, and
   review the guarded FontUtils/ImageRenderer overrides before releasing the plugin.
   BossTerm **1.2.169 contains the original host-account bridge but does not contain the
   new relay/preferences APIs**. No new host plugin API or BossConsole binary is required
   for these additions; the updated library is bundled privately inside the plugin.

Relay remains disabled by default. For staged debug testing, explicitly set
`BOSSTERM_RELAY_ENABLED=true` and `BOSSTERM_RELAY_URL=wss://<trusted-relay-origin>`
(or JVM properties `bossterm.relay.enabled` and `bossterm.relay.url`) on the app.
Native clients must opt in to the same trusted origin. Existing direct/LAN sharing and
older links keep their existing behavior. Once relay transport is selected, a connection
failure does not silently switch to a different transport.

## Paired development and CI

This draft is stacked on the host-account-sharing plugin change and BossTerm#434.
The draft-only path in [test.yml](../.github/workflows/test.yml) checks out an immutable
BossTerm revision and uses that checkout's compatible Gradle wrapper. Its job names and
summary explicitly identify paired-source validation. Ready-for-review PRs, main, and
release builds continue using the declared published Maven dependency without substitution.
A passing draft job therefore does not establish release readiness against BossTerm 1.2.169.

For paired local development, use a matching BossTerm checkout:

```bash
# From the plugin checkout; use the companion wrapper because it needs newer Gradle.
../bossterm-terminal-relay/gradlew -p . \
  -PbosstermSourceDir=../bossterm-terminal-relay test buildPluginJar
```

`bosstermSourceDir` may also be absolute. The resulting local JAR bundles the modified
library. Keep the draft CI source pin aligned with the reviewed companion commit; after
that change is published, bump the real Maven version and remove the temporary CI path.

Plugin tests cover provider absence, identity changes before and during each new RPC,
relay input validation, authenticated parameter forwarding, and cleanup ordering. Companion
BossTerm tests cover synchronous preference reset, stale responses, transport admission,
visibility, revocation, and reconnects. Backend tests cover ownership, settings concurrency,
one-use handoffs/tickets, and expected-identity rejection.

If any identity cleanup step fails, the remaining steps still run and sharing stays signed
out. Cleanup retries after 250 ms, 1 s and 4 s even without another identity event.
When retries are exhausted, automatic sharing stops and the sharing server is shut down
so old-account links cannot remain live. Sharing requires a plugin reload after this fallback. Errors
log only operation names and exception types, never payloads or bearer URLs.

Publisher shutdown waits synchronously for up to 3 seconds while the host bridge remains
open for row deletion. The host loader does not guarantee an IO dispatcher for disposal,
so an unload on the UI thread may pause for that bound. The host RPC provider delegates
to the asynchronous Supabase/Ktor client and does not marshal completion to Main/EDT.
The bounded wait preserves delete-before-bridge-close ordering; stale rows otherwise
expire after 90 seconds. A host-only asynchronous disposal API is a future improvement.
