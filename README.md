# R15 TV

R15 TV is a personal Android TV / mobile player build based on the public [FongMi/TV](https://github.com/FongMi/TV) source. GitHub Actions builds the APKs; no source checkout or Android build environment is required on your Windows PC.

## Current build path

- Public upstream source: `FongMi/TV`, branch `fongmi`.
- **No private source token is required.** Actions resolves the public branch and checks out the exact commit used for each build.
- Four APK variants are packaged: Android TV (`leanback`) and mobile (`mobile`), each for `arm64-v8a` and `armeabi-v7a`.
- The build sets the app label to `R15 TV`, uses application ID `com.dicky.r15tv` so it can coexist with the official app, and embeds a run-specific version.
- **No IPTV/live-playlist source or channel list is bundled.** Users must provide their own configuration or playlist.

This is a clean public-source baseline. It does not claim that every previously planned customization (for example, full Taiwan Traditional Chinese localization or additional AI subtitle UI changes) has already been implemented. Those changes must be applied and tested separately against this public source before being advertised as R15 TV features.

## Build with GitHub Actions

1. Open **Actions → Build R15 TV APKs**.
2. Select branch `public-source-build` in the workflow's branch selector and choose **Run workflow**.
3. Keep `publish_release` off for the first test. The workflow uses a debug build if no signing secrets are configured, then uploads the four APKs and build metadata as an artifact.
4. After a successful run, open that run and download the `R15 TV-<run number>` artifact.

A debug APK is for initial testing. Hosted runners do not preserve their debug signing key between runs, so a debug APK may not update an APK from an earlier run. It is not suitable for a public stable release.

### Optional: stable release signing

For signed release APKs and GitHub Releases, create **your own** Android release keystore and add all three repository secrets at **Settings → Secrets and variables → Actions**:

- `R15TV_KEYSTORE_B64`: Base64-encoded contents of the keystore.
- `R15TV_STORE_PASSWORD`: keystore password.
- `R15TV_KEY_PASSWORD`: key password.

The expected alias in the keystore is `r15tv`. If only some of these secrets are present, the workflow stops rather than silently building with incomplete signing information. The workflow publishes a GitHub Release only when signing secrets are available and `publish_release` is checked.

Never commit a keystore, password, or token to the repository. Do not copy an upstream project's private signing key.

## Traceability and checksums

Every successful build artifact includes:

- `SOURCE_REFS.json` and `SOURCE_COMMIT.txt`: exact public upstream source revision.
- `R15 TV-source.patch`: the exact tracked source edits for this build; apply it to the recorded source commit to reproduce the app ID, label, and version metadata.
- `BUILD_INFO.json`: build mode, version, source commit, and workflow URL.
- `SHA256SUMS.txt`: SHA-256 checksums for all APKs.
- `UPSTREAM-GPL-3.0-LICENSE.txt`: upstream app license.

The build records the exact source commit so a particular APK can be traced to its upstream baseline.

## License

The R15 TV build scripts and original documentation in this repository are provided under the repository's MIT license. The upstream FongMi/TV application is licensed under **GPL-3.0**; that license still applies to the application and derivative APKs. Follow upstream license obligations when distributing binaries or modified source, and review all third-party components' licenses.
