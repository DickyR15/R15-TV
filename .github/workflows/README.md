# GitHub Actions notes

The first workflow is intentionally a validation/preflight workflow. It does not pretend to compile the Android app while the source tree and build secrets are not available.

The full official build workflow can only be connected after confirming that the necessary upstream source repositories are accessible to the user and that this fork has its own release signing key. Do not copy the upstream private signing secrets.
