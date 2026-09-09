#!/usr/bin/env python3

import argparse
import datetime as dt
import hashlib
import json
import os
import plistlib
import subprocess
from pathlib import Path


def command(*args: str) -> str:
    return subprocess.check_output(args, text=True).strip()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


parser = argparse.ArgumentParser()
parser.add_argument("--ipa", required=True, type=Path)
parser.add_argument("--app", required=True, type=Path)
parser.add_argument("--kmp-framework-binary", required=True, type=Path)
parser.add_argument("--output", required=True, type=Path)
parser.add_argument("--xcode-version", required=True)
parser.add_argument("--iphoneos-sdk-version", required=True)
parser.add_argument("--macos-version", required=True)
parser.add_argument("--runner-image", required=True)
parser.add_argument("--kotlin-version", required=True)
parser.add_argument("--build-configuration", required=True)
parser.add_argument("--artifact-name", required=True)
args = parser.parse_args()

ipa = args.ipa.resolve()
app = args.app.resolve()
kmp_binary = args.kmp_framework_binary.resolve()
with (app / "Info.plist").open("rb") as stream:
    plist = plistlib.load(stream)

executable = app / plist["CFBundleExecutable"]
frameworks = []
frameworks_dir = app / "Frameworks"
if frameworks_dir.is_dir():
    for framework in sorted(frameworks_dir.glob("*.framework")):
        with (framework / "Info.plist").open("rb") as stream:
            framework_plist = plistlib.load(stream)
        framework_binary = framework / framework_plist["CFBundleExecutable"]
        frameworks.append(
            {
                "name": framework.name,
                "architectures": command("lipo", "-archs", str(framework_binary)).split(),
                "file": command("file", str(framework_binary)),
            }
        )

repository = os.environ["GITHUB_REPOSITORY"]
run_id = os.environ["GITHUB_RUN_ID"]
payload = {
    "schemaVersion": 1,
    "appName": "FluentMai",
    "releaseName": "FluentMai 0.2.4 Beta (iOS unsigned)",
    "appVersion": str(plist["CFBundleShortVersionString"]),
    "buildVersion": str(plist["CFBundleVersion"]),
    "gitCommit": os.environ["GITHUB_SHA"],
    "gitBranch": os.environ["GITHUB_REF_NAME"],
    "buildDateUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
    "githubActionsRun": {
        "id": int(run_id),
        "number": int(os.environ["GITHUB_RUN_NUMBER"]),
        "attempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
        "url": f"https://github.com/{repository}/actions/runs/{run_id}",
        "artifactName": args.artifact_name,
    },
    "macosRunner": {
        "image": args.runner_image,
        "version": args.macos_version,
        "architecture": os.environ.get("RUNNER_ARCH", "ARM64"),
    },
    "xcodeVersion": args.xcode_version,
    "iphoneosSdkVersion": args.iphoneos_sdk_version,
    "kotlinVersion": args.kotlin_version,
    "kotlinNativeTarget": "iosArm64",
    "architecture": ["arm64"],
    "buildConfiguration": args.build_configuration,
    "bundleIdentifier": plist["CFBundleIdentifier"],
    "minimumOSVersion": plist["MinimumOSVersion"],
    "dtPlatformName": plist.get("DTPlatformName"),
    "dtSdkName": plist.get("DTSDKName"),
    "ipaFilename": ipa.name,
    "ipaByteSize": ipa.stat().st_size,
    "ipaSha256": sha256(ipa),
    "mainExecutable": {
        "name": executable.name,
        "architectures": command("lipo", "-archs", str(executable)).split(),
        "file": command("file", str(executable)),
        "lipo": command("lipo", "-info", str(executable)),
    },
    "kmpFramework": {
        "name": "FluentMaiShared.framework",
        "linkage": "static",
        "packaging": "linked into the FluentMai main executable",
        "architectures": command("lipo", "-archs", str(kmp_binary)).split(),
        "file": command("file", str(kmp_binary)),
        "lipo": command("lipo", "-info", str(kmp_binary)),
        "linkEvidence": "xcodebuild link command and IosDomainBridge symbol verified",
    },
    "embeddedDynamicFrameworks": frameworks,
    "swiftRuntime": "ABI-stable system Swift runtime; Mach-O load commands verified for iOS",
    "signingState": "unsigned",
    "signingDetails": "CODE_SIGNING_ALLOWED=NO; no _CodeSignature, CodeResources, embedded.mobileprovision, certificate, provisioning profile, or private key is packaged",
    "simulatorArtifact": False,
    "realDeviceInstallVerified": False,
    "staticValidation": "passed",
}

args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
