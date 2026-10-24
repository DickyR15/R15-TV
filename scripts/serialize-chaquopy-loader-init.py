#!/usr/bin/env python3
"""Serialize Chaquopy's in-process Python startup and trace failures in older FongMi TV sources."""
from __future__ import annotations

import argparse
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tv-dir", type=Path, required=True)
    args = parser.parse_args()
    path = args.tv_dir / "chaquo/src/main/java/com/fongmi/chaquo/Loader.java"
    source = path.read_text(encoding="utf-8")
    if "PYTHON_START_LOCK" in source and "R15ChaquopyInitTrace" in source:
        print("Chaquopy startup lock and trace are already installed.")
        return

    old_import = "import com.chaquo.python.Python;\nimport com.github.catvod.utils.Path;"
    new_import = "import android.util.Log;\nimport com.chaquo.python.Python;\nimport com.github.catvod.utils.Path;"
    if old_import not in source:
        raise SystemExit(f"{path}: expected upstream imports not found")
    source = source.replace(old_import, new_import, 1)

    old_class = """public class Loader {

    private final PyObject app;

    public Loader() {
        if (!Python.isStarted()) Python.start(Platform.create());
        app = Python.getInstance().getModule("app");
    }"""
    new_class = '''public class Loader {

    private static final String TAG = "R15ChaquopyInitTrace";
    private static final Object PYTHON_START_LOCK = new Object();

    private final PyObject app;

    public Loader() {
        synchronized (PYTHON_START_LOCK) {
            Log.i(TAG, "Loader constructor thread=" + Thread.currentThread().getName()
                    + ", pythonStarted=" + Python.isStarted());
            try {
                if (!Python.isStarted()) {
                    Log.i(TAG, "Starting Chaquopy Python runtime");
                    Python.start(Platform.create());
                }
                Log.i(TAG, "Chaquopy Python runtime ready; pythonStarted=" + Python.isStarted());
                app = Python.getInstance().getModule("app");
                Log.i(TAG, "Python app module acquired");
            } catch (RuntimeException | Error e) {
                Log.e(TAG, "Chaquopy initialization failed", e);
                throw e;
            }
        }
    }'''
    if old_class not in source:
        raise SystemExit(f"{path}: expected old Loader constructor not found; refusing blind patch")
    source = source.replace(old_class, new_class, 1)
    if source.count("PYTHON_START_LOCK") != 2 or "synchronized (PYTHON_START_LOCK)" not in source:
        raise SystemExit("Chaquopy Python startup lock did not validate")
    if "Chaquopy initialization failed" not in source:
        raise SystemExit("Chaquopy initialization error trace did not validate")
    path.write_text(source, encoding="utf-8")
    print("Applied a class-level lock around Python.isStarted/start, Python.getInstance, and app module initialization.")
    print("Added success/error trace markers around the complete startup sequence.")


if __name__ == "__main__":
    main()
