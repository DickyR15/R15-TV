#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TV_DIR=${TV_DIR:-"$ROOT/sources/TV"}
SOURCE_REFS=${SOURCE_REFS:-"$ROOT/build/source-refs.json"}
DIST=${DIST:-"$ROOT/dist"}
RUN_NUMBER=${GITHUB_RUN_NUMBER:-local}
RUN_URL=${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-DickyR15/R15-TV}/actions/runs/${GITHUB_RUN_ID:-local}
APP_VERSION_CODE=${APP_VERSION_CODE:?APP_VERSION_CODE is required}
APP_VERSION_NAME=${APP_VERSION_NAME:?APP_VERSION_NAME is required}
BASE_VERSION_CODE=${BASE_VERSION_CODE:?BASE_VERSION_CODE is required}
BASE_VERSION_NAME=${BASE_VERSION_NAME:?BASE_VERSION_NAME is required}

rm -rf "$DIST"
mkdir -p "$DIST"

cp "$TV_DIR/Release/apk/leanback-arm64_v8a.apk" "$DIST/R15-TV-leanback-arm64-v8a.apk"
cp "$TV_DIR/Release/apk/leanback-armeabi_v7a.apk" "$DIST/R15-TV-leanback-armeabi-v7a.apk"
cp "$TV_DIR/Release/apk/mobile-arm64_v8a.apk" "$DIST/R15-TV-mobile-arm64-v8a.apk"
cp "$TV_DIR/Release/apk/mobile-armeabi_v7a.apk" "$DIST/R15-TV-mobile-armeabi-v7a.apk"

cp "$SOURCE_REFS" "$DIST/SOURCE_REFS.json"
cp "$TV_DIR/LICENSE.md" "$DIST/FONGMI-TV-LICENSE.md"
git -C "$TV_DIR" diff -- app/build.gradle > "$DIST/R15-TV-source-version.patch"
test -s "$DIST/R15-TV-source-version.patch"

(
  cd "$DIST"
  sha256sum R15-TV-leanback-arm64-v8a.apk R15-TV-leanback-armeabi-v7a.apk R15-TV-mobile-arm64-v8a.apk R15-TV-mobile-armeabi-v7a.apk > SHA256SUMS.txt
)

tv_repository=$(jq -r '.sources.TV.repository' "$SOURCE_REFS")
tv_branch=$(jq -r '.sources.TV.branch' "$SOURCE_REFS")
tv_sha=$(jq -r '.sources.TV.sha' "$SOURCE_REFS")

jq -n \
  --arg baseVersionName "$BASE_VERSION_NAME" \
  --argjson baseVersionCode "$BASE_VERSION_CODE" \
  --arg versionName "$APP_VERSION_NAME" \
  --argjson versionCode "$APP_VERSION_CODE" \
  --arg runNumber "$RUN_NUMBER" \
  --arg runUrl "$RUN_URL" \
  --arg repository "$tv_repository" \
  --arg branch "$tv_branch" \
  --arg sha "$tv_sha" \
  '{schema:1,baseVersion:{name:$baseVersionName,code:$baseVersionCode},buildVersion:{name:$versionName,code:$versionCode},source:{repository:$repository,branch:$branch,commit:$sha,url:("https://github.com/"+$repository+"/tree/"+$sha)},github:{runNumber:$runNumber,runUrl:$runUrl}}' \
  > "$DIST/BUILD_INFO.json"

cat > "$DIST/RELEASE_NOTES.md" <<EOF_NOTES
R15-TV public-source build ${APP_VERSION_NAME} (${APP_VERSION_CODE}).

- Upstream: https://github.com/$tv_repository/tree/$tv_sha
- Configured upstream branch: $tv_branch
- Base version: ${BASE_VERSION_NAME} (${BASE_VERSION_CODE})
- Build run: $RUN_URL
- APKs: Android TV (Leanback) and Mobile, each for arm64-v8a and armeabi-v7a.
- Content sources: none are bundled; users must configure their own source.
- Source changes: R15-TV-source-version.patch records the version-only edit applied during this build.
- Integrity: SHA256SUMS.txt contains the APK checksums.
- License: the upstream TV application is GPL-3.0; see FONGMI-TV-LICENSE.md and the upstream source repository.
EOF_NOTES
