# R15-TV

R15-TV is a personal Android TV / mobile player build based on the public [FongMi/TV](https://github.com/FongMi/TV) source. APK builds run on GitHub Actions; a Windows Android build environment is not required.

## Current status

- The public-source build workflow is available on branch [`public-source-build`](https://github.com/DickyR15/R15-TV/tree/public-source-build).
- It fetches public `FongMi/TV` source without a private source token.
- It is intended to produce Android TV and mobile APKs for `arm64-v8a` and `armeabi-v7a`.
- App label: `R15-TV`; package ID: `com.dicky.r15tv`.
- No IPTV/live-playlist sources or channel lists are bundled.

The public-source branch is a build baseline, not yet a verified successful build. Please select that branch in the workflow before running it. The existing `main` branch is retained separately.

## Run a test build

1. Open [Build R15-TV APKs in GitHub Actions](https://github.com/DickyR15/R15-TV/actions/workflows/build-apks.yml).
2. Select branch `public-source-build`.
3. Click **Run workflow** and leave `publish_release` unchecked for the first run.
4. Without release-signing secrets, the workflow attempts to produce debug APKs for testing. Download the `R15-TV-<run number>` artifact if the build succeeds.

A debug build is for initial validation only. Its signing key is not persistent between hosted runs, so it is not appropriate for stable updateable releases.

## Optional stable release signing

To create release-signed APKs, add your own signing key and all three repository secrets under **Settings → Secrets and variables → Actions**:

- `R15TV_KEYSTORE_B64`: Base64-encoded keystore.
- `R15TV_STORE_PASSWORD`: keystore password.
- `R15TV_KEY_PASSWORD`: key password.

The configured key alias is `r15tv`. The workflow does not require `R15TV_SOURCE_TOKEN` or `PRIVATE_SOURCE_TOKEN`. Do not commit a keystore or passwords, and do not use an upstream project's private signing key.

## Build metadata

The artifact is intended to include the exact upstream source revision, build metadata, SHA-256 checksums, upstream license, and an R15-TV source patch for traceability.

## Customization and license

The public-source branch currently provides a clean build baseline. Do not assume previously discussed changes such as complete Taiwan Traditional Chinese localization or additional AI subtitle controls are implemented until they have been applied and tested.

The upstream FongMi/TV application is licensed under GPL-3.0. Follow applicable source-distribution and license requirements for modified builds and binaries. Other third-party dependencies retain their own license terms.
