#!/vendor/bin/sh
# SPDX-License-Identifier: Apache-2.0
# The r1 is a data device: its calls have no audio path. The modem's stored UE mode is
# "voice centric" (AT+CEMODE 1), with which it leaves LTE whenever the network gives it no
# voice service: on a network without 2G and 3G that is all service gone, data included.
# "Data centric" (CS/PS mode 2) keeps it on LTE; IMS still registers where it can.
#
# Android sets this through IRadioNetwork.setUsageSetting, which the stock HIDL RIL does not
# have, so it is set here on the spare AT channel gsm0710muxd links for atcid (not shipped).
# The modem keeps the mode in its NV data, so this writes once.

P=/dev/radio/atci1

ask() {   # ask <command>: its answer lines, without blanks
    answer=$(
        # The channel drops the first command after a pause: a bare AT goes ahead
        { sleep 1; printf 'AT\r' > $P; sleep 1; printf '%s\r' "$1" > $P; } &
        timeout 5 cat $P
    )
    printf '%s\n' "$answer" | tr -d '\r' | grep -v '^$'
}

waited=0
while [ ! -c $P ]; do
    if [ $waited -ge 60 ]; then
        log -p w -t r1-modem "no spare AT channel; UE mode left as it is"
        exit 0
    fi
    sleep 1
    waited=$((waited + 1))
done

for try in 1 2 3 4 5; do
    mode=$(ask 'AT+CEMODE?' | grep '^+CEMODE: ')
    case "$mode" in
    "+CEMODE: 2")
        exit 0
        ;;
    "+CEMODE: "*)
        ask 'AT+CEMODE=2' > /dev/null
        if [ "$(ask 'AT+CEMODE?' | grep '^+CEMODE: ')" = "+CEMODE: 2" ]; then
            log -p i -t r1-modem "UE mode set to data centric (was ${mode#+CEMODE: })"
            exit 0
        fi
        ;;
    esac
    sleep 3
done
log -p w -t r1-modem "the modem did not take the data centric UE mode (${mode:-no answer})"
exit 0
