# GitHub Actions

## Build R15-TV APKs from public FongMi source

Manually builds the public `FongMi/TV` `fongmi` branch. No personal source
token is needed. If the persistent signing secrets are not configured, the
workflow uses a one-run test keystore and uploads an Actions Artifact only;
it does not publish a GitHub Release. A release can only be published from
`main`, after a persistent signing key is configured and
`publish_release` is enabled.

## R15-TV Public Source Preflight

Checks the source manifest and verifies that the public upstream branch is
reachable. It does not compile the APK.
