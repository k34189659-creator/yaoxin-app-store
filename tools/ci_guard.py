#!/usr/bin/env python3
"""決定這次建置是否需要發布：只有 versionCode 高於 catalog 內最新版才發布。"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import publish  # noqa: E402  同資料夾的發布工具，重用 APK 解析邏輯


def emit(publish_now: bool, reason: str) -> int:
    print(reason, flush=True)
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as fh:
            fh.write(f"publish={'true' if publish_now else 'false'}\n")
            fh.write(f"reason={reason}\n")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--catalog", default="catalog.json")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--require-signer", default="",
                    help="要求的簽章憑證 SHA-256；不符時一律不發布")
    args = ap.parse_args()

    info = publish.read_apk(Path(args.apk).expanduser().resolve())
    catalog_path = Path(args.catalog)
    catalog = json.loads(catalog_path.read_text(encoding="utf-8")) if catalog_path.is_file() else {"apps": []}

    latest = {}
    for app in catalog.get("apps", []):
        if app.get("packageName") == info.package:
            latest = app.get("latest") or {}
            break

    if args.require_signer:
        actual = (info.signer_sha256 or "").lower()
        expected = args.require_signer.lower()
        if actual != expected:
            print(f"::error::APK 簽章不符：預期 {expected}，實際 {actual or '（讀不到）'}", flush=True)
            return emit(False, f"簽章不符（實際 {actual or '無'}）→ 停止發布")

    latest_code = int(latest.get("versionCode") or 0)
    latest_name = latest.get("versionName") or "（無）"
    head = (f"APK: {info.package} {info.version_name}({info.version_code})；"
            f"catalog 最新: {latest_name}({latest_code})")

    if args.force:
        return emit(True, f"{head} → --force，強制發布")
    if info.version_code > latest_code:
        return emit(True, f"{head} → 版本較新，發布")
    return emit(False, f"{head} → 版本未更新，跳過發布")


if __name__ == "__main__":
    raise SystemExit(main())
