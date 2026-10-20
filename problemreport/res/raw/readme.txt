r1 problem report
=================

Saved by Settings > System > Problem report. Please send the whole .zip file
with your bug report.

What is in it
-------------
summary.txt     build, kernel, how long it was up, battery, memory, storage,
                how the last start ended, and what you typed
system.txt      model, chip, radio version, the Kernel Tweaks and CPU profile
                settings, /proc/meminfo
logcat.txt      the Android log since the start (all buffers)
kernel.txt      the kernel log (dmesg)
last-start.txt  how the last start ended (Android's boot reason) and what it
                left in pstore, with the kernel's crash records by name
last-start-kernel.txt   the kernel log of the last start (pstore console)
last-start-logcat.txt   the end of the last start's Android log (pstore pmsg)
last-start-crash-*.txt  the kernel's crash records (oops or panic). They are
                numbered from 0 again at every start, so one can be older than
                the last start: its first line says which crash it was
selinux-denials.txt  SELinux denials from the kernel and Android logs, of now
                and of the last start
crashes.txt     crash reports: the newest tombstones and ANR traces
dropbox.txt     Android's crash records of the last week, the newest of each
                kind
power.txt       dumpsys battery, power, thermalservice, deviceidle, alarm
devices.txt     Wi-Fi, Bluetooth and airplane mode on or off; dumpsys input,
                display, sensorservice, audio, usb
apps.txt        installed apps and versions, running processes, dumpsys
                activity activities, meminfo

The logs of the last start are only kept when the r1 restarts, not when it
was switched off or the battery ran empty.

What is left out or blanked
---------------------------
- this r1's serial number, IMEI, MEID, IMSI, SIM serial and phone number, and
  anything that looks like one: serial numbers and IMEIs by their name or
  their check digit, phone numbers in international form
- Wi-Fi network names and passwords, the saved networks and the one in use;
  the Wi-Fi, Bluetooth and connectivity dumps
- the Bluetooth name and the names and addresses of paired devices, the
  device name, the second half of every MAC address
- accounts and e-mail addresses
- contacts, messages, photos: nothing is read from them
- the names of your files: every path or link into the shared storage is cut
  to "<hidden>"

The logs can still name Bluetooth devices nearby or apps you used. Have a look
before you post it in public.
