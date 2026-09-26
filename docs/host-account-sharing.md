# Terminal sharing through BossConsole login

The terminal uses `PluginContext.authDataProvider.currentUser` for account identity and
`supabaseDataProvider.rpc` for publishing and discovering live sessions. BossConsole owns
login, refresh and logout. No access/refresh token is copied into the plugin or BossTerm's
auth file, and there is no separate terminal sign-in prompt.

Account sharing follows BossTerm's existing publishing, auto-share and auto-connect settings.
On sign-out or account switch, previous share links and viewers are revoked before the new
identity is made available. This also stops manually created shares, since those shares have
account links too. Other devices' automatically connected sessions are disconnected.
The old account's registry rows may remain visible until their 90-second heartbeat timeout;
their links are already invalid. Explicitly ending a share or unloading while signed in
removes this process's rows through the host.

Missing auth/database providers leave account sharing signed out; local terminals still work.
Plugin disposal stops account services and detaches the host provider. Reloading within the
same classloader uses a stable identity/transport facade, never standalone credentials.

## Paired rollout

1. BossConsole migration `20260926000000_terminal_session_host_rpc.sql` was applied to
   the shared backend used by the debug build on 2026-09-25. Apply it to other backends before rollout.
   It adds owner-only, SECURITY INVOKER RPCs and rejects an expected identity that differs
   from `auth.uid()`. The existing `terminal_sessions` RLS policies remain authoritative.
2. BossTerm 1.2.169 contains `HostAccountSessions` / `AccountSessionSource` and the
   initialization safety fix. The plugin and CI use this published Maven dependency.
3. Build/release this terminal plugin. No new host plugin API or BossConsole binary is needed.

For paired local development before the library release, use a source composite build:

```bash
# From this plugin checkout; use BossTerm's wrapper because its Android plugin needs newer Gradle.
../bossterm-host-account/gradlew -p . \
  -PbosstermSourceDir=../bossterm-host-account test buildPluginJar
```

`bosstermSourceDir` may instead be an absolute path to the matching BossTerm checkout.
The local JAR includes the modified library. On backends without the migration, account registry
calls fail and retry; the local build alone does not make the backend feature available.

Validation: plugin tests cover restored login, provider absence, account changes during RPC,
and cleanup ordering. BossTerm tests cover host transport, stale directory responses and
provider reinstallation. `supabase/tests/terminal_session_host_rpc_test.sql` covers database
ownership, anonymous access, stale identity rejection and deletion.

CI validates the published BossTerm 1.2.169 artifact on Linux and Windows. The source
composite property remains available for opt-in local development. FontUtils and
ImageRenderer overrides match the release source apart from attribution comments.

If any identity cleanup step fails, the remaining steps still run and sharing stays signed
out. Cleanup retries after 250 ms, 1 s and 4 s even without another identity event.
Persistent failures stay signed out until a later host identity event or reload. Errors
log only operation names and exception types, never payloads or bearer URLs.
