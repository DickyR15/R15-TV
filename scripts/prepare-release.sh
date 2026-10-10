#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TV_DIR=${TV_DIR:-"$ROOT/sources/TV"}
SOURCE_REFS=${SOURCE_REFS:-"$ROOT/build/source-refs.json"}
DIST=${DIST:-"$ROOT/dist"}
RUN_NUMBER=${GITHUB_RUN_NUMBER:-local}
RUN_URL=${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-iptvorganization/R15-TV}/actions/runs/${GITHUB_RUN_ID:-local}
APP_VERSION_CODE=${APP_VERSION_CODE:?APP_VERSION_CODE is required}
APP_VERSION_NAME=${APP_VERSION_NAME:?APP_VERSION_NAME is required}
BASE_VERSION_CODE=${BASE_VERSION_CODE:?BASE_VERSION_CODE is required}
BASE_VERSION_NAME=${BASE_VERSION_NAME:?BASE_VERSION_NAME is required}

rm -rf "$DIST"
mkdir -p "$DIST"

variants=(
  "leanbackArmeabi_v7a|TV-leanback-armeabi-v7a.apk"
  "leanbackArm64_v8a|TV-leanback-arm64-v8a.apk"
  "mobileArmeabi_v7a|TV-mobile-armeabi-v7a.apk"
  "mobileArm64_v8a|TV-mobile-arm64-v8a.apk"
)
apk_names=()
for entry in "${variants[@]}"; do
  variant=${entry%%|*}
  name=${entry#*|}
  apk=$(find "$TV_DIR/app/build/outputs/apk/$variant/release" -maxdepth 1 -type f -name '*.apk' -print -quit)
  test -n "$apk" && test -s "$apk"
  cp "$apk" "$DIST/$name"
  apk_names+=("$name")
done

cp "$SOURCE_REFS" "$DIST/SOURCE_REFS.json"
(
  cd "$DIST"
  sha256sum "${apk_names[@]}" > SHA256SUMS.txt
)

jq -n \
  --arg baseVersionName "$BASE_VERSION_NAME" \
  --argjson baseVersionCode "$BASE_VERSION_CODE" \
  --arg versionName "$APP_VERSION_NAME" \
  --argjson versionCode "$APP_VERSION_CODE" \
  --arg runNumber "$RUN_NUMBER" \
  --arg runUrl "$RUN_URL" \
  '{schema:1,baseVersion:{name:$baseVersionName,code:$baseVersionCode},buildVersion:{name:$versionName,code:$versionCode},github:{runNumber:$runNumber,runUrl:$runUrl}}' \
  > "$DIST/BUILD_INFO.json"

cat > "$DIST/RELEASE_NOTES.md" <<EOF_NOTES
Automated public build of TV ${APP_VERSION_NAME} (${APP_VERSION_CODE}).

Base source version: ${BASE_VERSION_NAME} (${BASE_VERSION_CODE}).

Source commits:
- TV: $(jq -r '.sources.TV.sha' "$SOURCE_REFS")
- media: $(jq -r '.sources.media.sha' "$SOURCE_REFS")
- VividLib: $(jq -r '.sources.VividLib.sha' "$SOURCE_REFS")
- sherpa-onnx: $(jq -r '.sources["sherpa-onnx"].sha' "$SOURCE_REFS")

Build run: ${RUN_URL}

All four APKs use the persistent R15 TV release signing key stored as GitHub Actions secrets. Verify files with SHA256SUMS.txt and inspect BUILD_INFO.json for the effective install version.
EOF_NOTES

echo "release_name=TV ${APP_VERSION_NAME}" >> "${GITHUB_OUTPUT:-/dev/null}"
