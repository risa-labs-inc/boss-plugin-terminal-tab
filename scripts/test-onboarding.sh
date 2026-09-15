#!/usr/bin/env bash
set -euo pipefail

plugin_dir=$(cd "$(dirname "$0")/.." && pwd)
revision=$(tr -d '\r\n' < "$plugin_dir/.bossterm-onboarding-revision")
if [[ ! "$revision" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'Expected a full BossTerm commit SHA in .bossterm-onboarding-revision' >&2
  exit 1
fi
deps_dir="$plugin_dir/build/onboarding-deps/$revision"
source_dir="$deps_dir/source"
maven_dir="$deps_dir/maven"
# Native package configuration requires a numeric version even for Maven-only builds.
# The complete revision in the repository path isolates these review artifacts.
dependency_version="1.2.0-SNAPSHOT"
mkdir -p "$deps_dir"
if [[ ! -d "$source_dir/.git" ]]; then
  git init "$source_dir"
fi
git -C "$source_dir" fetch --depth=1 https://github.com/shivanshu-risa/BossTerm.git "$revision"
git -C "$source_dir" checkout --detach FETCH_HEAD
test "$(git -C "$source_dir" rev-parse HEAD)" = "$revision"
(
  cd "$source_dir"
  env -u RELEASE_BUILD -u INTELLIJ_DEPENDENCIES_BOT APP_VERSION=1.2.0 ./gradlew \
    :bossterm-core-mpp:publishJvmPublicationToMavenLocal \
    :bossterm-core-mpp:publishKotlinMultiplatformPublicationToMavenLocal \
    :compose-ui:publishDesktopPublicationToMavenLocal \
    :compose-ui:publishKotlinMultiplatformPublicationToMavenLocal \
    "-Dmaven.repo.local=$maven_dir" --no-daemon
)
cd "$plugin_dir"
./gradlew test buildPluginJar \
  "-PbosstermMavenRepo=$maven_dir" "-PbosstermVersion=$dependency_version" --no-daemon
