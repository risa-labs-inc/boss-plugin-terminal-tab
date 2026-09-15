# Building the onboarding PR

The setup bridge requires the companion BossTerm source change. Its new APIs are not in
the production Maven dependency yet. Keep this PR in draft until that change is released
and the normal `bosstermVersion` is updated to the published version.

For review and CI, run:

```sh
bash scripts/test-onboarding.sh
```

The script fetches the immutable commit recorded in `.bossterm-onboarding-revision`, builds
the BossTerm core and Compose publications into `build/onboarding-deps/<commit>/maven`, and tests/packages this
plugin against those isolated artifacts. They use local version `1.2.0-SNAPSHOT` with a numeric
base to satisfy native package configuration; the full source commit identifies the review build.
It does not publish externally or use the user's
Maven local cache. CI uses the same command. The normal plugin API dependency must be
available as described in the repository's build instructions.

Before merging, publish the reviewed BossTerm commit through its release process, update
the production dependency, and restore CI to testing that released dependency directly.
