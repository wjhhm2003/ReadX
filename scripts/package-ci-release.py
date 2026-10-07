#!/usr/bin/env python3
"""Stage the actual ordinary Preview and describe a verified CI prerelease (stdlib only)."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parent.parent
FILENAME = "app-preview.apk"


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def stage(root):
    """Copy the actual ordinary APK together with its R8 mapping and AGP metadata."""
    source = root / "app/build/outputs/apk/preview"
    metadata = json.loads((source / "output-metadata.json").read_text(encoding="utf-8"))
    if metadata["applicationId"] != "io.readx.app" or metadata["variantName"] != "preview":
        raise ValueError("Expected ReadX Preview metadata")
    elements = metadata["elements"]
    if len(elements) != 1 or elements[0].get("filters"):
        raise ValueError("Expected one universal APK, not split APKs")
    element = elements[0]
    if Path(element["outputFile"]).name != element["outputFile"]:
        raise ValueError("APK output must stay in the Preview directory")
    apk = source / element["outputFile"]
    if not apk.is_file() or apk.stat().st_size == 0:
        raise ValueError("Missing or empty Preview APK")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+-preview-ci\.[0-9]+\.[0-9]+", element["versionName"]):
        raise ValueError("Expected an ordinary CI Preview version (no bundled OCR)")
    if not isinstance(element["versionCode"], int) or not 100000 < element["versionCode"] <= 2_100_000_000:
        raise ValueError("Expected a CI versionCode")
    mapping = root / "app/build/outputs/mapping/preview/mapping.txt"
    if not mapping.is_file():
        raise ValueError("Missing R8 mapping for this Preview")
    release = root / "build/ci/release"
    reports = root / "build/ci/reports"
    release.mkdir(parents=True, exist_ok=True)
    reports.mkdir(parents=True, exist_ok=True)
    target = release / FILENAME
    shutil.copyfile(apk, target)
    shutil.copyfile(mapping, reports / "mapping.txt")
    write_json(reports / "apk-metadata.json", {
        "filename": target.name, "versionName": element["versionName"],
        "versionCode": element["versionCode"], "bytes": target.stat().st_size, "sha256": digest(target),
    })


def finalize(root, environment, certificate_sha256):
    if not re.fullmatch(r"[a-f0-9]{64}", certificate_sha256):
        raise ValueError("Invalid signing certificate SHA-256")
    commit = environment["GITHUB_SHA"]
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Invalid source revision")
    if environment["GITHUB_REF"] != "refs/heads/main" or environment["GITHUB_EVENT_NAME"] not in ("push", "workflow_dispatch"):
        raise ValueError("Only a verified main push/manual run may publish")
    number, attempt, run_id = (int(environment[key]) for key in ("GITHUB_RUN_NUMBER", "GITHUB_RUN_ATTEMPT", "GITHUB_RUN_ID"))
    if min(number, attempt, run_id) < 1:
        raise ValueError("Invalid workflow run identity")
    release, reports = root / "build/ci/release", root / "build/ci/reports"
    info = json.loads((reports / "apk-metadata.json").read_text(encoding="utf-8"))
    apk = release / FILENAME
    if info["filename"] != FILENAME or info["bytes"] != apk.stat().st_size or info["sha256"] != digest(apk):
        raise ValueError("Staged APK changed after its build")
    expected_suffix = f"-preview-ci.{number}.{attempt}"
    if not info["versionName"].endswith(expected_suffix):
        raise ValueError("APK version does not match this workflow run")
    if info["versionCode"] != 100000 + number:
        raise ValueError("Version code does not match this workflow run")
    base_version = info["versionName"].removesuffix(expected_suffix)
    tag = f"ci-v{base_version}-{number}.{attempt}-{commit[:7]}"
    title = f"ReadX {base_version} · CI #{number}.{attempt} · {commit[:7]}"
    run_url = f"{environment['GITHUB_SERVER_URL']}/{environment['GITHUB_REPOSITORY']}/actions/runs/{run_id}"
    write_json(release / "build-info.json", {
        "tag": tag, "title": title, "commit": commit, "run": run_url, "runAttempt": attempt,
        "builtAtUtc": datetime.now(timezone.utc).isoformat(), "applicationId": "io.readx.app", "bundledOcr": False, "apk": info,
        "signing": {"type": "Dedicated CI Preview debug key; not production signing", "certificateSha256": certificate_sha256},
        "verification": ["JVM tests", "Android lint", "API 36 instrumentation (optional private/network/model tests may skip)",
                         "Pinned dictionary checks", "R8 Preview installation and startup", "APK signature verification"],
    })
    # Avoid raw book data and private key material in publicly downloadable metadata.
    notes = f"""## 自动交付 / 普通版 Preview

- 源码提交：{commit}；测试与构建记录：{run_url}（attempt {attempt}）。
- app-preview.apk：普通版，不内置 OCR 模型，CI 不下载 OCR 模型。
- APK 经过 R8 / 资源压缩；使用持久化 CI Preview 调试密钥，不是正式 Release 签名。
- 单元测试、Lint、API 36 模拟器测试及实际 R8 APK 安装启动验证通过后发布；私人样书、主动联网、需外部模型等可选测试可能跳过，详情以 Actions 报告为准。
- APK SHA-256 见 SHA256SUMS.txt；真实版本、源码与签名证书指纹见 build-info.json。

### 安装提醒

包名为 io.readx.app，同一 CI 签名下可以更新。CI versionCode 为 {info['versionCode']}。
本机旧 Preview 使用不同密钥，不能直接覆盖安装。不要用卸载或清空数据解决签名冲突；完整书库/批注备份恢复尚未实现，请先确认数据保留方案。
此发布是自动化开发预览，并不代表所有设备与复杂书籍均已验收。
"""
    (release / "release-notes.md").write_text(notes, encoding="utf-8")
    files = sorted(p for p in release.iterdir() if p.is_file() and p.name != "SHA256SUMS.txt")
    (release / "SHA256SUMS.txt").write_text("".join(f"{digest(p)}  {p.name}\n" for p in files), encoding="utf-8")
    print(f"Prepared {tag} from {commit}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("stage")
    finishing = commands.add_parser("finalize")
    finishing.add_argument("--certificate-sha256", required=True)
    args = parser.parse_args()
    if args.command == "stage":
        stage(ROOT)
    else:
        finalize(ROOT, os.environ, args.certificate_sha256)


if __name__ == "__main__":
    main()
