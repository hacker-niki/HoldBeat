#!/usr/bin/env python3
"""Run against an English-language test emulator with HoldBeat installed.

    python3 tools/smoke_test.py emulator-5554

Resets HoldBeat's test data. Set ADB to override the adb executable.
"""

import argparse
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.tonechronometer.app"


class Emulator:
    def __init__(self, serial):
        if not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("Supply a dedicated Android emulator serial, e.g. emulator-5554")
        self.command = [os.environ.get("ADB", "adb"), "-s", serial]

    def adb(self, *args):
        return subprocess.check_output([*self.command, *args], timeout=25).decode().strip()

    def shell(self, *args):
        return self.adb("shell", *args)

    def launch(self):
        self.shell("input", "keyevent", "KEYCODE_WAKEUP")
        self.shell("wm", "dismiss-keyguard")
        self.shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")

    def node(self, selector):
        # Wait for asynchronous dialogs, transitions, and the accessibility tree.
        path = "/data/local/tmp/holdbeat-smoke.xml"
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            self.shell("uiautomator", "dump", path)
            tree = ET.fromstring(self.adb("exec-out", "cat", path))
            for node in tree.iter("node"):
                if selector in (node.get("text"), node.get("content-desc"),
                                node.get("resource-id", "").removeprefix(PACKAGE + ":id/")):
                    return node
        raise AssertionError(f"Control missing: {selector}")

    def tap(self, selector):
        bounds = self.node(selector).get("bounds")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", bounds))
        self.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def select(self, row, value):
        self.tap(row)
        self.tap(value)

    def timer_state(self):
        raw = self.shell("run-as", PACKAGE, "cat", "shared_prefs/countdown.xml")
        return ET.fromstring(raw).findtext("string[@name='state']")

    def wait_for_state(self, expected, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.timer_state() == expected:
                return
            time.sleep(0.1)
        raise AssertionError(f"Expected {expected}, got {self.timer_state()}")


def run(device):
    if device.shell("getprop", "ro.kernel.qemu") != "1":
        raise ValueError("This test resets HoldBeat's data and needs a dedicated emulator")
    if not device.shell("pm", "path", PACKAGE).startswith("package:"):
        raise ValueError("Install the debug APK first")
    device.shell("pm", "clear", PACKAGE)
    device.launch()

    device.tap("settings")
    for precision, sample in (("Tenths of a second", "00:12.3"),
                              ("Hundredths of a second", "00:12.34"),
                              ("Milliseconds", "00:12.345")):
        device.select("precision_setting", precision)
        assert device.node("precision_preview").get("text") == sample, f"Unexpected {precision} preview"
    device.select("prep_setting", "10 seconds")
    device.select("mode_setting", "Stopwatch")
    device.node("Timer mode, Stopwatch")
    device.shell("am", "force-stop", PACKAGE)
    device.launch()
    device.tap("settings")
    for row, expected in (("precision_setting", "Milliseconds"),
                          ("prep_setting", "10 seconds"), ("mode_setting", "Stopwatch")):
        assert expected in device.node(row).get("content-desc", ""), f"{row} did not persist"
    device.tap("settings_back")

    device.tap("15 s")
    device.tap("primary")
    device.wait_for_state("PREPARING", 1)
    device.shell("input", "keyevent", "KEYCODE_HOME")
    device.shell("input", "keyevent", "KEYCODE_SLEEP")
    # Kill only this debug app after leaving its Activity. Force-stop would cancel its alarms.
    pid = device.shell("pidof", PACKAGE)
    assert pid.isdecimal(), f"Unexpected app process ID: {pid}"
    device.shell("run-as", PACKAGE, "kill", "-9", pid)
    until_exit = time.monotonic() + 2
    while PACKAGE in device.shell("ps", "-A", "-o", "NAME").splitlines():
        if time.monotonic() >= until_exit:
            raise AssertionError("App process still running during preparation")
        time.sleep(0.1)
    device.wait_for_state("RUNNING", 13)
    device.wait_for_state("OVERTIME", 19)
    device.launch()
    device.tap("Stop hold")
    device.wait_for_state("FINISHED", 2)
    stopped = device.node("dial").get("content-desc", "")
    result = re.search(r"(\d+):(\d{2})[.](\d{3})", stopped)
    assert result, f"Missing milliseconds: {stopped}"
    minutes, seconds, millis = map(int, result.groups())
    assert (minutes * 60 + seconds) * 1000 + millis > 15_000, f"Stopwatch stopped at target: {stopped}"
    time.sleep(0.3)
    assert device.node("dial").get("content-desc") == stopped, "Result kept advancing after stop"

    device.tap("settings")
    device.select("mode_setting", "Countdown")
    device.select("prep_setting", "Off")
    device.select("precision_setting", "Whole seconds")
    device.tap("settings_back")
    device.tap("15 s")
    device.tap("primary")
    device.wait_for_state("RUNNING", 1)
    device.shell("input", "keyevent", "KEYCODE_SLEEP")
    device.wait_for_state("FINISHED", 18)
    device.launch()
    device.node("Start again")
    print("PASS: settings persistence, locked preparation and target, millisecond result, countdown")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", help="dedicated emulator serial")
    run(Emulator(parser.parse_args().serial))
