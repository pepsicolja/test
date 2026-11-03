#!/usr/bin/env python3
"""Install the supplied Flappy Bird fixture in a local ARM32 Android emulator.

This checks boot/ABI, records graphics information, launches the game and captures
the screen. It does not claim playable speed, sound, or phone compatibility.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--wait", type=int, default=300)
    args = parser.parse_args()
    expected = "a3e6958ce2100966f4e207778e4cdbe72788214148c7f4bfd042ba365498deb3"
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != expected:
        parser.error("APK differs from the inspected, user-supplied fixture")
    if not (args.serial.startswith("emulator-") or re.fullmatch(r"127\.0\.0\.1:\d{1,5}", args.serial)):
        parser.error("This host test requires an explicit local emulator serial")
    args.out.mkdir(parents=True, exist_ok=True)

    def adb(*command, timeout=30, check=True, binary=False):
        result = subprocess.run(
            [args.adb, "-s", args.serial, *command], capture_output=True,
            timeout=timeout, check=check,
        )
        return result.stdout if binary else result.stdout.decode(errors="replace").strip()

    deadline = time.monotonic() + args.wait
    while True:
        result = adb("shell", "getprop", "sys.boot_completed", check=False)
        if result == "1":
            break
        if time.monotonic() >= deadline:
            raise RuntimeError("Android has not reported a completed boot")
        time.sleep(5)

    abi = adb("shell", "getprop", "ro.product.cpu.abi")
    if abi not in ("armeabi-v7a", "armeabi"):
        raise RuntimeError(f"Expected ARM32 guest, received {abi!r}")
    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Target does not identify as an emulator")
    (args.out / "properties.txt").write_text(adb("shell", "getprop"))
    (args.out / "surfaceflinger.txt").write_text(adb("shell", "dumpsys", "SurfaceFlinger"))
    print("Guest boot complete; ABI:", abi, flush=True)
    install = adb("install", "-r", str(args.apk), timeout=120)
    (args.out / "install.txt").write_text(install)
    if "Success" not in install:
        raise RuntimeError(f"Installation did not succeed: {install}")
    package = adb("shell", "dumpsys", "package", "com.dotgears.flappybird")
    (args.out / "package.txt").write_text(package)
    if "primaryCpuAbi=armeabi" not in package:
        raise RuntimeError("Package manager has not confirmed an ARM ABI")
    adb("logcat", "-c")
    adb("shell", "input", "keyevent", "82")
    launch = adb("shell", "am", "start", "-W", "-n", "com.dotgears.flappybird/com.dotgears.flappy.SplashScreen")
    (args.out / "launch.txt").write_text(launch)
    if "Status: ok" not in launch:
        raise RuntimeError(f"Activity launch failed: {launch}")
    time.sleep(15)
    frame = adb("exec-out", "screencap", "-p", binary=True)
    if not frame.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("Guest did not produce a PNG screen capture")
    (args.out / "flappy-launch.png").write_bytes(frame)
    (args.out / "logcat.txt").write_text(adb("logcat", "-d", "-t", "500"))
    activities = adb("shell", "dumpsys", "activity", "activities")
    (args.out / "activities.txt").write_text(activities)
    resumed = [line for line in activities.splitlines() if "mResumedActivity:" in line]
    foreground = any("com.dotgears.flappybird/" in line for line in resumed)
    (args.out / "result.json").write_text(json.dumps({
        "guest_boot_completed": True, "guest_abi": abi,
        "package_arm_abi_confirmed": True, "activity_launch_ok": True,
        "screenshot_captured": True, "app_remains_foreground": foreground,
        "gameplay_verified": False,
        "phone_backend_verified": False,
    }, indent=2))
    if not foreground:
        raise RuntimeError("Game did not remain in foreground; inspect captured logs and image")
    print("Launch captured. Inspect screenshot/logs before claiming rendering or gameplay.", flush=True)


if __name__ == "__main__":
    main()
