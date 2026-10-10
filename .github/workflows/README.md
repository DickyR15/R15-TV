# GitHub Actions notes

## Build R15-TV APKs

The APK workflow checks out the public `FongMi/TV` repository at the `fongmi` branch. It resolves and records the exact commit before building; no `R15TV_SOURCE_TOKEN` or `PRIVATE_SOURCE_TOKEN` is used.

If all three R15-TV release-signing secrets are absent, the workflow produces debug APKs for initial testing. Configure the three `R15TV_*` signing secrets to produce release-signed APKs. A release is published only when `publish_release` is selected.

The source project is GPL-3.0. Build artifacts include the upstream license and exact source commit. The app does not bundle any IPTV/live-playlist sources.

## Public Source Preflight

The preflight workflow verifies the source manifest and checks that the public upstream branch is reachable. It does not compile the Android app; use **Actions → Build R15-TV APKs** for an actual build.
