#!/usr/bin/env bash
#
# Auto-bump the privately-bundled BossTerm version in build.gradle.kts.
#
# Run from the repo root. Reads the current bundled `bossterm-compose` version,
# resolves the latest release that is actually live on Maven Central, and — if a
# newer one exists — rewrites both version lines in build.gradle.kts and writes a
# PR body to pr-body.md. The calling workflow does the git/PR/merge.
#
# Intentionally deterministic (no AI). Portable sed/awk so it also runs on macOS
# for local testing.
#
# Inputs (env):
#   TARGET_VERSION  optional — force a specific bossterm version (manual/test runs)
#   GITHUB_OUTPUT   optional — GitHub Actions step-output file (falls back to stdout)
#
# Outputs (to $GITHUB_OUTPUT):
#   changed=true|false, new_bt=<ver>, new_plugin=<ver>
set -euo pipefail

GRADLE_FILE="build.gradle.kts"
META_URL="https://repo1.maven.org/maven2/com/risaboss/bossterm-compose/maven-metadata.xml"
OUT="${GITHUB_OUTPUT:-/dev/stdout}"

emit() { printf '%s\n' "$1" >> "$OUT"; }
noop() { echo "→ $1"; emit "changed=false"; exit 0; }

# 1. Current versions from build.gradle.kts
current_bt=$(sed -n 's/^val bosstermVersion = "\(.*\)"/\1/p' "$GRADLE_FILE")
current_plugin=$(sed -n 's/^version = "\(.*\)"/\1/p' "$GRADLE_FILE")
[ -n "$current_bt" ]     || { echo "ERROR: could not read bosstermVersion from $GRADLE_FILE" >&2; exit 1; }
[ -n "$current_plugin" ] || { echo "ERROR: could not read plugin version from $GRADLE_FILE" >&2; exit 1; }
echo "Current: bossterm=$current_bt plugin=$current_plugin"

# 2. Latest live release on Maven Central (or forced TARGET_VERSION)
latest="${TARGET_VERSION:-}"
if [ -z "$latest" ]; then
  latest=$(curl -fsSL "$META_URL" | sed -n 's:.*<release>\(.*\)</release>.*:\1:p' | head -1)
fi
[ -n "$latest" ] || { echo "ERROR: could not resolve latest bossterm version from Maven Central" >&2; exit 1; }
echo "Maven Central latest release: $latest"

# 3. Newer than what we bundle? (semver-safe; sort -V)
if [ "$latest" = "$current_bt" ]; then
  noop "Already on the latest bossterm ($current_bt) — nothing to do"
fi
newest=$(printf '%s\n%s\n' "$current_bt" "$latest" | sort -V | tail -1)
if [ "$newest" != "$latest" ]; then
  noop "Current $current_bt is newer than Maven's $latest (TARGET_VERSION override?) — nothing to do"
fi

# 4. Belt-and-suspenders: the POM must actually be served before we open a PR.
#    Guards against the metadata-vs-CDN lag that bites a too-early manual bump.
pom_url="https://repo1.maven.org/maven2/com/risaboss/bossterm-compose/${latest}/bossterm-compose-${latest}.pom"
if ! curl -fsI "$pom_url" >/dev/null 2>&1; then
  echo "::warning::bossterm $latest is in maven-metadata but its POM is not served yet; will retry next run"
  noop "Artifact $latest not yet resolvable on Maven Central"
fi

# 5. New plugin version: +1 patch from current (matches the repo's release cadence)
IFS='.' read -r MA MI PA <<EOF
$current_plugin
EOF
new_plugin="${MA}.${MI}.$((PA + 1))"
echo "Bumping: bossterm $current_bt → $latest, plugin $current_plugin → $new_plugin"

# 6. Rewrite build.gradle.kts (deterministic, anchored on exact lines)
notes_url="https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v${latest}.md"
tmp="$(mktemp)"
awk -v old_bt="$current_bt" -v new_bt="$latest" \
    -v old_pv="$current_plugin" -v new_pv="$new_plugin" \
    -v notes="$notes_url" '
  $0 == "version = \"" old_pv "\"" {
    print "// " new_pv ": auto-bumped bundled BossTerm to " new_bt
    print "// (release notes: " notes ")."
    print "version = \"" new_pv "\""
    next
  }
  /requires re-releasing this plugin, not BossConsole\./ {
    print
    print "// " new_bt ": auto-bumped bundled BossTerm (release notes: " notes ")."
    next
  }
  $0 == "val bosstermVersion = \"" old_bt "\"" {
    print "val bosstermVersion = \"" new_bt "\""
    next
  }
  { print }
' "$GRADLE_FILE" > "$tmp"

# Sanity: both version lines must have actually changed.
grep -q "^val bosstermVersion = \"${latest}\"$" "$tmp" || { echo "ERROR: bossterm line rewrite failed" >&2; exit 1; }
grep -q "^version = \"${new_plugin}\"$" "$tmp"          || { echo "ERROR: plugin version rewrite failed" >&2; exit 1; }
mv "$tmp" "$GRADLE_FILE"

# 7. PR body — embed BossTerm release notes if published yet, else link the diff.
raw_notes="https://raw.githubusercontent.com/kshivang/BossTerm/main/docs/release-notes/v${latest}.md"
{
  printf 'Bumps the privately-bundled `bossterm-compose` from **%s → %s** and the plugin version to **%s**.\n\n' \
    "$current_bt" "$latest" "$new_plugin"
  if curl -fsSL "$raw_notes" -o bt-notes.tmp 2>/dev/null; then
    echo "## BossTerm $latest release notes"
    echo
    cat bt-notes.tmp
    rm -f bt-notes.tmp
  else
    printf 'BossTerm %s release notes are not published yet. Changelog: https://github.com/kshivang/BossTerm/compare/v%s...v%s\n' \
      "$latest" "$current_bt" "$latest"
  fi
  printf '\n_Opened automatically by the BossTerm Auto-Bump workflow once `%s` was live on Maven Central._\n\n' "$latest"
  echo "🤖 Generated with [Claude Code](https://claude.com/claude-code)"
} > pr-body.md

emit "changed=true"
emit "new_bt=${latest}"
emit "new_plugin=${new_plugin}"
echo "✅ Prepared bump to bossterm $latest (plugin $new_plugin)"
