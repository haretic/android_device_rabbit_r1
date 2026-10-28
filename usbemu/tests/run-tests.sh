#!/bin/sh
# Host only: no Android build, adb, mounts, loop devices or actual formatters.
set -eu
cd "$(dirname "$0")/../.."
sh -n usbemu/r1-usbemu.sh
python3 usbemu/tests/test_usbemu.py
python3 usbemu/tests/check_sources.py
