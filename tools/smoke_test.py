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

    def timer_progress(self):
        raw = self.shell("run-as", PACKAGE, "cat", "shared_prefs/countdown.xml")
        prefs = ET.fromstring(raw)
        step = prefs.find("int[@name='setsStep']")
        return prefs.findtext("string[@name='state']"), int(step.get("value")) if step is not None else None

    def wait_for_state(self, expected, timeout, step=None):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            state, current_step = self.timer_progress()
            if state == expected and (step is None or step == current_step):
                return
            time.sleep(0.1)
        raise AssertionError(f"Expected {expected} (step {step}), got {self.timer_progress()}")

    def replace_number(self, field, number):
        self.tap(field)
        self.shell("input", "keyevent", "KEYCODE_MOVE_END", *(["KEYCODE_DEL"] * 4))
        self.shell("input", "text", str(number))

    def save_set_plan(self, work, rest, count):
        for field, value in zip(("work_seconds", "rest_seconds", "set_count"), (work, rest, count)):
            self.replace_number(field, value)
        self.tap("android:id/button1")

    def kill_background_process(self):
        # Force-stop would also cancel the alarms under test.
        pid = self.shell("pidof", PACKAGE)
        assert pid.isdecimal(), f"Unexpected app process ID: {pid}"
        self.shell("run-as", PACKAGE, "kill", "-9", pid)
        deadline = time.monotonic() + 2
        while PACKAGE in self.shell("ps", "-A", "-o", "NAME").splitlines():
            if time.monotonic() >= deadline:
                raise AssertionError("Background app process did not exit")
            time.sleep(0.1)


def run_sets(device):
    device.tap("settings")
    device.select("mode_setting", "Sets")
    device.tap("settings_back")
    device.tap("custom")
    fields = ("work_seconds", "rest_seconds", "set_count")
    assert [device.node(field).get("text") for field in fields] == ["30", "15", "3"]
    device.save_set_plan(8, 14, 2)
    device.tap("Start workout")
    device.wait_for_state("RUNNING", 2, step=0)
    device.wait_for_state("RUNNING", 11, step=1)
    device.tap("Pause workout")
    device.wait_for_state("PAUSED", 2, step=1)
    paused = device.node("dial").get("content-desc", "")
    assert "rest" in paused.lower(), paused
    time.sleep(1.1)
    assert device.node("dial").get("content-desc") == paused, "Paused rest kept advancing"
    device.tap("Resume workout")
    device.wait_for_state("RUNNING", 2, step=1)
    device.shell("input", "keyevent", "KEYCODE_HOME")
    device.shell("input", "keyevent", "KEYCODE_SLEEP")
    device.kill_background_process()
    device.wait_for_state("RUNNING", 17, step=2)
    device.wait_for_state("FINISHED", 11)
    assert "mWakefulness=Asleep" in device.shell("dumpsys", "power"), "Device woke during the workout"
    device.launch()
    print("PASS: work, rest pause/resume, locked next set after process death, finish without final rest")


def run_short_rest(device):
    device.tap("custom")
    device.save_set_plan(11, 2, 2)
    device.tap("Start workout")
    device.wait_for_state("RUNNING", 2, step=0)
    device.shell("input", "keyevent", "KEYCODE_HOME")
    device.shell("input", "keyevent", "KEYCODE_SLEEP")
    device.kill_background_process()
    device.wait_for_state("RUNNING", 14, step=1)
    # The second work alarm must already be registered. A new exact alarm scheduled
    # only at the start of this two-second rest can be deferred by Android.
    device.kill_background_process()
    device.wait_for_state("RUNNING", 3.5, step=2)
    device.wait_for_state("FINISHED", 14)
    device.launch()
    print("PASS: a two-second rest stays on time after the app process exits twice")


def run(device):
    if device.shell("getprop", "ro.kernel.qemu") != "1":
        raise ValueError("This test resets HoldBeat's data and needs a dedicated emulator")
    if not device.shell("pm", "path", PACKAGE).startswith("package:"):
        raise ValueError("Install the debug APK first")
    device.shell("pm", "clear", PACKAGE)
    device.launch()
    run_sets(device)
    run_short_rest(device)

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
    device.kill_background_process()
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
