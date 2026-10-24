#!/usr/bin/env python3
"""Add the configurable long-press playback speed row to R15 TV settings."""

from __future__ import annotations

import argparse
from pathlib import Path
import re


TV_ROW = """        <androidx.appcompat.widget.LinearLayoutCompat
            android:id="@+id/longPress"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:background="@drawable/selector_item"
            android:focusable="true"
            android:focusableInTouchMode="true"
            android:orientation="horizontal">

            <com.google.android.material.textview.MaterialTextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="16dp"
                android:text="@string/speed_setting_long_press"
                android:textColor="@color/white"
                android:textSize="18sp" />

            <com.google.android.material.textview.MaterialTextView
                android:id="@+id/longPressText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="end"
                android:textColor="@color/white"
                android:textSize="18sp"
                tools:text="3 倍" />

        </androidx.appcompat.widget.LinearLayoutCompat>

"""

MOBILE_ROW = """            <androidx.appcompat.widget.LinearLayoutCompat
                android:id="@+id/longPress"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                android:background="@drawable/shape_item"
                android:orientation="horizontal">

                <com.google.android.material.textview.MaterialTextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginEnd="16dp"
                    android:text="@string/speed_setting_long_press"
                    android:textColor="@color/white"
                    android:textSize="16sp" />

                <com.google.android.material.textview.MaterialTextView
                    android:id="@+id/longPressText"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:gravity="end"
                    android:textColor="@color/white"
                    android:textSize="16sp"
                    tools:text="3 倍" />

            </androidx.appcompat.widget.LinearLayoutCompat>

"""

JAVA_METHOD = """    private void setLongPressSpeed(View view) {
        float current = SpeedSetting.getLongPress();
        float[] options = {2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 4.5f, 5.0f};
        float next = options[0];
        for (float option : options) {
            if (option > current + 0.001f) {
                next = option;
                break;
            }
        }
        SpeedSetting.putLongPress(next);
        setLongPressSpeedText();
    }

    private void setLongPressSpeedText() {
        String speed = SpeedSetting.formatValue(SpeedSetting.getLongPress());
        if (speed.endsWith(".0")) speed = speed.substring(0, speed.length() - 2);
        mBinding.longPressText.setText(speed + " 倍");
    }

"""


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one patch anchor, got {count}: {old[:80]!r}")
    return text.replace(old, new, 1)


def patch_layout(path: Path, is_tv: bool) -> None:
    text = path.read_text(encoding="utf-8")
    if 'android:id="@+id/longPress"' in text:
        if 'android:id="@+id/longPressText"' not in text:
            raise SystemExit(f"{path}: long-press row is partial; refusing to continue.")
        return

    pattern = re.compile(
        r'(?m)^(?P<indent>[ ]*)<androidx\.appcompat\.widget\.LinearLayoutCompat\n'
        r'[ ]+android:id="@\+id/background"'
    )
    match = pattern.search(text)
    if not match:
        raise SystemExit(f"{path}: could not locate the background-playback row.")
    indent = match.group("indent")
    row = TV_ROW if is_tv else MOBILE_ROW
    row = "\n".join((indent + line if line else line) for line in row.rstrip("\n").split("\n")) + "\n\n"
    text = text[:match.start()] + row + text[match.start():]
    path.write_text(text, encoding="utf-8")


def patch_java(path: Path, mobile: bool) -> None:
    text = path.read_text(encoding="utf-8")
    import_anchor = "import com.fongmi.android.tv.setting.PlayerSetting;\n"
    import_line = "import com.fongmi.android.tv.setting.SpeedSetting;\n"
    if import_line not in text:
        text = replace_once(text, import_anchor, import_line + import_anchor, path)

    # Upstream 5.6.1 used a scaleText row; 5.8.x uses a renderText row and no scaleText.
    scale_init = "        mBinding.scaleText.setText((scale = ResUtil.getStringArray(R.array.select_scale))[PlayerSetting.getScale()]);\n"
    current_init = "        mBinding.bufferText.setText(String.valueOf(PlayerSetting.getBuffer()));\n"
    if "setLongPressSpeedText();" not in text:
        if scale_init in text:
            text = replace_once(text, scale_init, scale_init + "        setLongPressSpeedText();\n", path)
        elif current_init in text:
            text = replace_once(text, current_init, current_init + "        setLongPressSpeedText();\n", path)
        else:
            raise SystemExit(f"{path}: could not locate a version-compatible initView() label anchor")

    click_anchors = [
        "        mBinding.scale.setOnClickListener(this::onScale);\n" if mobile
        else "        mBinding.scale.setOnClickListener(this::setScale);\n",
        "        mBinding.render.setOnClickListener(this::setRender);\n",
    ]
    click_line = "        mBinding.longPress.setOnClickListener(this::setLongPressSpeed);\n"
    if click_line not in text:
        matches = [anchor for anchor in click_anchors if anchor in text]
        if len(matches) != 1:
            raise SystemExit(f"{path}: expected one version-compatible render/scale click anchor, found {len(matches)}")
        text = replace_once(text, matches[0], matches[0] + click_line, path)

    if "private void setLongPressSpeed(View view)" not in text:
        method_anchor = "    private void onBuffer(View view) {\n"
        text = replace_once(text, method_anchor, JAVA_METHOD + method_anchor, path)

    path.write_text(text, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tv-dir", required=True, type=Path)
    args = parser.parse_args()
    tv = args.tv_dir

    patch_layout(tv / "app/src/leanback/res/layout/activity_setting_player.xml", True)
    patch_layout(tv / "app/src/mobile/res/layout/fragment_setting_player.xml", False)
    patch_java(tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPlayerActivity.java", False)
    patch_java(tv / "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingPlayerFragment.java", True)

    for path in (
        tv / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPlayerActivity.java",
        tv / "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingPlayerFragment.java",
    ):
        content = path.read_text(encoding="utf-8")
        for required in ("SpeedSetting.getLongPress()", "SpeedSetting.putLongPress(next)", 'mBinding.longPressText.setText(speed + " 倍")'):
            if required not in content:
                raise SystemExit(f"{path}: required long-press speed logic missing: {required}")

    print("Added long-press speed control (2x through 5x in 0.5x steps) to Leanback and Mobile settings.")


if __name__ == "__main__":
    main()
