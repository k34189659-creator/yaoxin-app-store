#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

API = "https://api.github.com"
USER_AGENT = "yaoxin-store-publisher/1.0"
MAX_RETRIES = 3


class PublishError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class ApkInfo:
    path: Path
    package: str
    version_name: str
    version_code: int
    label: str
    sha256: str
    size: int
    signer_sha256: str | None


def log(message: str) -> None:
    print(message, flush=True)


def run(cmd: list[str]) -> str:
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise PublishError("TOOL", f"命令失敗：{cmd[0]}：{result.stderr.strip()[:500]}")
    return result.stdout


def aapt2_path() -> str:
    override = os.environ.get("AAPT2")
    if override:
        return override
    for candidate in ("/opt/aapt2/aapt2", "aapt2"):
        try:
            subprocess.run([candidate, "version"], capture_output=True, check=True)
            return candidate
        except Exception:
            continue
    raise PublishError("TOOL", "找不到 aapt2；可用 AAPT2 環境變數指定")



def _apk_signer_sha256(path: Path) -> str | None:
    """讀出 APK 簽章憑證的 SHA-256（相容新舊 apksigner 輸出，失敗時退回 keytool）。"""
    digest_re = re.compile(r"(?i)SHA-?256\s*digest:\s*([0-9a-f]{64})")
    try:
        out = run(["apksigner", "verify", "--print-certs", str(path)])
        m = digest_re.search(out)
        if m:
            return m.group(1).lower()
    except PublishError:
        pass
    try:
        out = run(["keytool", "-printcert", "-jarfile", str(path)])
        m = re.search(r"(?i)SHA256:\s*([0-9A-F:]{95})", out)
        if m:
            return m.group(1).replace(":", "").lower()
    except PublishError:
        pass
    return None


def read_apk(path: Path) -> ApkInfo:
    if not path.is_file():
        raise PublishError("APK_NOT_FOUND", f"APK 不存在：{path}")
    output = run([aapt2_path(), "dump", "badging", str(path)])
    package_match = re.search(
        r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", output
    )
    if not package_match:
        raise PublishError("BAD_APK", "aapt2 找不到 package／versionName／versionCode")
    package = package_match.group(1)
    try:
        version_code = int(package_match.group(2))
    except ValueError:
        raise PublishError("BAD_APK", "versionCode 不是整數")
    version_name = package_match.group(3).strip()
    if not version_name:
        raise PublishError("BAD_APK", "versionName 是空白")

    labels: dict[str, str] = {}
    for raw in output.splitlines():
        m = re.match(r"application-label(?:-([^:]+))?:'(.*)'$", raw)
        if m:
            labels[m.group(1) or "default"] = m.group(2)
    label = (
        labels.get("zh-TW")
        or labels.get("zh-Hant")
        or labels.get("zh-CN")
        or labels.get("default")
        or package
    ).strip()

    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    signer = _apk_signer_sha256(path)
    return ApkInfo(
        path=path,
        package=package,
        version_name=version_name,
        version_code=version_code,
        label=label,
        sha256=digest.hexdigest(),
        size=path.stat().st_size,
        signer_sha256=signer,
    )


def sanitize(value: str, fallback: str = "app") -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "-", value.strip()).strip("-._")
    return cleaned or fallback


def tag_prefix_for(package: str, config: dict) -> str:
    app = (config.get("apps") or {}).get(package) or {}
    prefix = app.get("tagPrefix")
    if prefix:
        return sanitize(str(prefix), package.rsplit(".", 1)[-1])
    return sanitize(package.rsplit(".", 1)[-1], "app")


def display_name_for(info: ApkInfo, config: dict, override: str | None) -> str:
    if override:
        return override.strip()
    app = (config.get("apps") or {}).get(info.package) or {}
    return str(app.get("name") or info.label or info.package)


def extract_changelog(path: Path, version: str) -> str | None:
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8", errors="replace")
    heading = re.compile(rf"^##\s+v?{re.escape(version)}\b.*$", re.MULTILINE)
    m = heading.search(text)
    if not m:
        return None
    start = m.end()
    next_heading = re.search(r"^##\s+", text[start:], re.MULTILINE)
    end = start + next_heading.start() if next_heading else len(text)
    section = text[start:end].strip()
    return section[:12000] if section else None


class GitHub:
    def __init__(self, token: str):
        self.token = token

    def _request(self, method: str, url: str, data: bytes | None = None,
                 extra_headers: dict[str, str] | None = None) -> tuple[int, dict, bytes]:
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": USER_AGENT,
        }
        if extra_headers:
            headers.update(extra_headers)
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=180) as response:
                body = response.read()
                return response.status, dict(response.headers), body
        except urllib.error.HTTPError as error:
            body = error.read()
            try:
                detail = json.loads(body.decode("utf-8", "replace"))
                message = detail.get("message") or str(detail)
            except Exception:
                message = body.decode("utf-8", "replace")[:500]
            raise PublishError(f"HTTP_{error.code}", f"GitHub API {error.code}：{message}") from None
        except urllib.error.URLError as error:
            raise PublishError("NETWORK", f"GitHub 連線失敗：{error.reason}") from None

    def get(self, path: str) -> dict:
        _, _, body = self._request("GET", API + path)
        return json.loads(body.decode("utf-8"))

    def get_optional(self, path: str) -> dict | None:
        try:
            return self.get(path)
        except PublishError as error:
            if error.code == "HTTP_404":
                return None
            raise

    def post(self, path: str, payload: dict) -> dict:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        _, _, body = self._request("POST", API + path, data,
                                   {"Content-Type": "application/json"})
        return json.loads(body.decode("utf-8"))

    def put(self, path: str, payload: dict) -> dict:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        _, _, body = self._request("PUT", API + path, data,
                                   {"Content-Type": "application/json"})
        return json.loads(body.decode("utf-8"))

    def upload_asset(self, upload_url: str, asset_name: str, apk: Path) -> dict:
        base = upload_url.split("{", 1)[0]
        url = base + "?name=" + urllib.parse.quote(asset_name)
        data = apk.read_bytes()
        _, _, body = self._request(
            "POST", url, data,
            {"Content-Type": "application/vnd.android.package-archive"},
        )
        return json.loads(body.decode("utf-8"))

    def download(self, url: str) -> bytes:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=180) as response:
            return response.read()


def read_token(args: argparse.Namespace) -> str:
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        return token.strip()
    path = Path(args.token_file).expanduser() if args.token_file else (
        Path.home() / ".config" / "yaoxin-store" / "github_token"
    )
    if path.is_file():
        return path.read_text(encoding="utf-8").strip()
    raise PublishError(
        "NO_TOKEN",
        "找不到 GitHub Token；請設定 GITHUB_TOKEN，或把 fine-grained PAT 放在 "
        f"{path}（chmod 600）。",
    )


def load_config(args: argparse.Namespace) -> dict:
    path = Path(args.config)
    if not path.is_file():
        raise PublishError("NO_CONFIG", f"找不到設定檔：{path}")
    config = json.loads(path.read_text(encoding="utf-8"))
    if args.repo:
        config["repository"] = args.repo
    if not config.get("repository"):
        raise PublishError("NO_CONFIG", "config.json 缺少 repository")
    return config


def release_path(repo: str, tag: str) -> str:
    return f"/repos/{repo}/releases/tags/{urllib.parse.quote(tag)}"


def ensure_release(gh: GitHub, repo: str, branch: str, tag: str, name: str, body: str) -> dict:
    existing = gh.get_optional(release_path(repo, tag))
    if existing:
        log(f"Release 已存在：{tag}，檢查資產…")
        return existing
    payload = {
        "tag_name": tag,
        "target_commitish": branch,
        "name": name,
        "body": body,
        "draft": False,
        "prerelease": False,
    }
    release = gh.post(f"/repos/{repo}/releases", payload)
    log(f"已建立 Release：{tag}")
    return release


def ensure_asset(gh: GitHub, release: dict, asset_name: str, apk: ApkInfo, verify: bool) -> dict:
    assets = release.get("assets") or []
    existing = next((a for a in assets if a.get("name") == asset_name), None)
    if existing:
        if existing.get("size") == apk.size:
            if verify:
                log("檢查既有資產 SHA-256…")
                try:
                    remote = gh.download(existing["browser_download_url"])
                    remote_sha = hashlib.sha256(remote).hexdigest()
                except Exception as error:
                    raise PublishError("ASSET_VERIFY", f"無法下載既有資產驗證：{error}") from None
                if remote_sha != apk.sha256:
                    raise PublishError(
                        "RELEASE_CONFLICT",
                        f"同一個 tag 的既有 APK SHA-256 不同（遠端 {remote_sha[:12]}…，本機 {apk.sha256[:12]}…）；"
                        "請提高版本號，不要覆蓋已發布的版本。",
                    )
                log("既有資產 SHA-256 一致，跳過上傳。")
            else:
                log("既有資產大小一致，跳過上傳。")
            return existing
        raise PublishError(
            "RELEASE_CONFLICT",
            f"Release {release['tag_name']} 已有同名資產 {asset_name}，但大小不同；"
            "請提高版本號或先刪除該 Release。",
        )
    log(f"上傳 APK：{asset_name}（{apk.size} bytes）…")
    asset = gh.upload_asset(release["upload_url"], asset_name, apk.path)
    log("APK 上傳完成。")
    return asset


def fetch_catalog(gh: GitHub, repo: str, branch: str, path: str) -> tuple[dict, str | None]:
    encoded = urllib.parse.quote(path)
    contents = gh.get_optional(f"/repos/{repo}/contents/{encoded}?ref={urllib.parse.quote(branch)}")
    if not contents:
        return {"schemaVersion": 1, "apps": []}, None
    try:
        data = base64.b64decode(contents["content"]).decode("utf-8")
        return json.loads(data), contents["sha"]
    except Exception as error:
        raise PublishError("BAD_CATALOG", f"catalog.json 無法解析：{error}") from None


def catalog_entry(info: ApkInfo, config: dict, release: dict, asset: dict,
                  display_name: str, prefix: str, tag: str, changelog: str) -> dict:
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    return {
        "versionName": info.version_name,
        "versionCode": info.version_code,
        "tag": tag,
        "releaseName": release.get("name") or f"{display_name} {info.version_name}",
        "releaseUrl": release.get("html_url"),
        "downloadUrl": asset.get("browser_download_url"),
        "assetName": asset.get("name"),
        "sha256": info.sha256,
        "sizeBytes": info.size,
        "publishedAt": release.get("published_at") or now,
        "changelog": changelog,
        "signerSha256": info.signer_sha256,
    }


def merge_catalog(catalog: dict, info: ApkInfo, display_name: str, prefix: str,
                  entry: dict) -> dict:
    apps = catalog.setdefault("apps", [])
    app = next((a for a in apps if a.get("packageName") == info.package), None)
    if app is None:
        app = {
            "id": info.package,
            "name": display_name,
            "packageName": info.package,
            "tagPrefix": prefix,
            "latest": entry,
            "versions": [entry],
        }
        apps.append(app)
    else:
        app["name"] = display_name
        app["tagPrefix"] = prefix
        versions = [v for v in (app.get("versions") or []) if v.get("tag") != entry["tag"]]
        versions.append(entry)
        versions.sort(key=lambda v: (int(v.get("versionCode") or 0), v.get("publishedAt") or ""), reverse=True)
        app["versions"] = versions[:100]
        app["latest"] = max(app["versions"], key=lambda v: int(v.get("versionCode") or 0))
    apps.sort(key=lambda a: str(a.get("name") or "").lower())
    catalog["schemaVersion"] = 1
    catalog["generatedAt"] = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    return catalog


def commit_catalog(gh: GitHub, repo: str, branch: str, path: str, catalog: dict,
                   sha: str | None, info: ApkInfo, display_name: str,
                   prefix: str, entry: dict) -> dict:
    encoded_path = urllib.parse.quote(path)
    message = f"catalog: {info.package} {info.version_name} ({info.version_code})"
    current_catalog = catalog
    current_sha = sha
    for attempt in range(MAX_RETRIES):
        payload = {
            "message": message,
            "content": base64.b64encode(json.dumps(current_catalog, ensure_ascii=False, indent=2).encode("utf-8")).decode("ascii"),
            "branch": branch,
        }
        if current_sha:
            payload["sha"] = current_sha
        try:
            return gh.put(f"/repos/{repo}/contents/{encoded_path}", payload)
        except PublishError as error:
            if error.code in ("HTTP_409", "HTTP_422") and attempt < MAX_RETRIES - 1:
                log("catalog.json 更新衝突，重新讀取後重試…")
                current_catalog, current_sha = fetch_catalog(gh, repo, branch, path)
                current_catalog = merge_catalog(current_catalog, info, display_name, prefix, entry)
                continue
            raise
    raise PublishError("CATALOG_RETRY", "catalog 更新失敗")

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="發布 APK 到瑤芯應用商店（GitHub Releases）")
    parser.add_argument("--apk", required=True, help="APK 路徑")
    parser.add_argument("--config", default="config.json", help="商店設定檔")
    parser.add_argument("--repo", help="覆寫 owner/repo")
    parser.add_argument("--branch", help="覆寫預設分支")
    parser.add_argument("--app-id", help="指定 App 套件（預設讀 APK）")
    parser.add_argument("--name", help="覆寫 App 顯示名稱")
    parser.add_argument("--tag-prefix", help="覆寫版本標籤前綴")
    parser.add_argument("--notes", help="更新內容")
    parser.add_argument("--notes-file", help="更新內容檔案")
    parser.add_argument("--changelog", help="CHANGELOG.md 路徑，用於自動抓取版本段落")
    parser.add_argument("--asset-name", help="覆寫 GitHub Release 資產名稱")
    parser.add_argument("--token-file", help="GitHub Token 檔案（預設 ~/.config/yaoxin-store/github_token）")
    parser.add_argument("--dry-run", action="store_true", help="只顯示計畫，不上傳")
    parser.add_argument("--yes", action="store_true", help="不詢問確認（DSHA 已確認時使用）")
    parser.add_argument("--verify-existing", action="store_true", default=True,
                        help="既有資產以 SHA-256 驗證（預設開啟）")
    parser.add_argument("--no-verify-existing", dest="verify_existing", action="store_false")
    parser.add_argument("--json", action="store_true", help="輸出 JSON 結果")
    args = parser.parse_args(argv)

    try:
        info = read_apk(Path(args.apk).expanduser().resolve())
        if args.app_id and args.app_id != info.package:
            raise PublishError("APP_MISMATCH", f"--app-id {args.app_id} 與 APK 套件 {info.package} 不一致")
        config = load_config(args)
        repo = config["repository"]
        branch = args.branch or config.get("branch") or "main"
        prefix = sanitize(args.tag_prefix or tag_prefix_for(info.package, config), "app")
        display = display_name_for(info, config, args.name)
        tag = f"{prefix}-v{sanitize(info.version_name, 'version')}"
        asset_name = args.asset_name or f"yaoxin-{prefix}-{sanitize(info.version_name, 'version')}.apk"
        changelog = args.notes or ""
        if args.notes_file:
            changelog = Path(args.notes_file).read_text(encoding="utf-8").strip()
        if not changelog:
            changelog = extract_changelog(Path(args.changelog), info.version_name) if args.changelog else None
        if not changelog:
            changelog = f"版本 {info.version_name}（versionCode {info.version_code}）"
        body = f"""## {display} {info.version_name}

- 套件：`{info.package}`
- versionCode：`{info.version_code}`
- SHA-256：`{info.sha256}`

{changelog}
"""
        plan = {
            "app": display,
            "package": info.package,
            "versionName": info.version_name,
            "versionCode": info.version_code,
            "tag": tag,
            "assetName": asset_name,
            "sha256": info.sha256,
            "sizeBytes": info.size,
            "repository": repo,
            "branch": branch,
            "signerSha256": info.signer_sha256,
        }
        log("發布計畫：")
        log(json.dumps(plan, ensure_ascii=False, indent=2))
        if args.dry_run:
            return 0
        if not args.yes:
            answer = input("確認發布到瑤芯應用商店？[y/N] ").strip().lower()
            if answer not in ("y", "yes"):
                log("已取消，未上傳。")
                return 1
        token = read_token(args)
        gh = GitHub(token)
        # 先確認 repo 可讀，避免建立了 release 才發現 catalog 權限不足。
        repo_info = gh.get(f"/repos/{repo}")
        default_branch = repo_info.get("default_branch") or branch
        branch = args.branch or config.get("branch") or default_branch
        release = ensure_release(gh, repo, branch, tag, f"{display} {info.version_name}", body)
        asset = ensure_asset(gh, release, asset_name, info, args.verify_existing)
        catalog, sha = fetch_catalog(gh, repo, branch, config.get("catalogPath") or "catalog.json")
        entry = catalog_entry(info, config, release, asset, display, prefix, tag, changelog.strip())
        catalog = merge_catalog(catalog, info, display, prefix, entry)
        commit_catalog(gh, repo, branch, config.get("catalogPath") or "catalog.json", catalog, sha, info, display, prefix, entry)
        log("catalog.json 已更新。")
        log(f"Release：{release.get('html_url')}")
        log(f"下載：{asset.get('browser_download_url')}")
        log(f"SHA-256：{info.sha256}")
        if args.json:
            print(json.dumps({"ok": True, "plan": plan, "release": release.get("html_url"),
                              "download": asset.get("browser_download_url"),
                              "catalog": f"https://github.com/{repo}/blob/{branch}/{config.get('catalogPath') or 'catalog.json'}"},
                             ensure_ascii=False))
        return 0
    except PublishError as error:
        log(f"發布失敗（{error.code}）：{error.message}")
        return 2 if error.code in ("APK_NOT_FOUND", "BAD_APK", "NO_CONFIG", "APP_MISMATCH") else 3
    except KeyboardInterrupt:
        log("已取消。")
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
