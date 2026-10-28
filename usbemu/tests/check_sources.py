#!/usr/bin/env python3
"""Resource/protocol integration checks, without compiling Android or Java."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
app = root/'app'
strings = {n.attrib['name'] for n in ET.parse(app/'res/values/strings.xml').getroot() if n.tag == 'string'}
for xml in app.rglob('*.xml'):
    ET.parse(xml)
    for resource in re.findall(r'@string/(\w+)', xml.read_text()):
        assert resource in strings, (xml, resource)
    assert '\u00b7' not in xml.read_text(), xml
for java in app.rglob('*.java'):
    for resource in re.findall(r'R\.string\.(\w+)', java.read_text()):
        assert resource in strings, (java, resource)
    for resource in re.findall(r'R\.drawable\.(\w+)', java.read_text()):
        assert (app/'res/drawable'/(resource+'.xml')).exists(), (java, resource)
    assert '\u00b7' not in java.read_text(), java
script = (root/'r1-usbemu.sh').read_text()
assert '>"$F/lun.0/forced_eject"' in script
for forbidden in ('/UDC', '/configs/', 'sys.usb.config ', 'losetup ', '/dev/block/'):
    assert forbidden not in script, forbidden
manifest = ET.parse(app/'AndroidManifest.xml').getroot()
a = '{http://schemas.android.com/apk/res/android}'
assert manifest.attrib[a+'sharedUserId'] == 'android.uid.system'
services = manifest.find('application').findall('service')
assert any(s.attrib.get(a+'foregroundServiceType') == 'specialUse|microphone' for s in services)
print('Resource, manifest and gadget-ownership checks passed')

# Optional parser when installed on the host; never invokes a Java compiler.
try:
    from tree_sitter import Language, Parser
    import tree_sitter_java
except ImportError:
    print('Java syntax parser unavailable (resource/protocol checks still ran)')
else:
    parser = Parser(Language(tree_sitter_java.language()))
    for source in app.rglob('*.java'):
        assert not parser.parse(source.read_bytes()).root_node.has_error, source
    print('Java syntax parsing passed (no compilation)')

# Parse native syntax too; this is a parser, not a compiler or a build.
try:
    from tree_sitter import Language, Parser
    import tree_sitter_cpp
except ImportError:
    print('C++ syntax parser unavailable')
else:
    parser = Parser(Language(tree_sitter_cpp.language()))
    for source in (root/'native').rglob('*.cpp'):
        assert not parser.parse(source.read_bytes()).root_node.has_error, source
    print('C++ syntax parsing passed (no compilation)')

# Cross-component boundaries, generation handshake and mute defaults.
io = (root/'native/Io.cpp').read_text()
audio = (app/'src/cat/kenny/r1/usbemu/AudioBridge.java').read_text()
service = (app/'src/cat/kenny/r1/usbemu/SessionService.java').read_text()
activity = (app/'src/cat/kenny/r1/usbemu/UsbActivity.java').read_text()
policy = (root.parent/'sepolicy/system_ext/r1_usbemu_io.te').read_text()
permissions = {p.attrib[a+'name'] for p in manifest.findall('uses-permission')}
assert 'android.permission.RECORD_AUDIO' in permissions
assert 'android.permission.FOREGROUND_SERVICE_MICROPHONE' in permissions
for channel in ('r1-usbemu-input', 'r1-usbemu-audio'):
    assert channel in io and channel in (root/'r1-usbemu.rc').read_text()
    assert f'socket {channel} seqpacket 0660 root system' in (root/'r1-usbemu.rc').read_text()
assert 'SO_PEERCRED' in io and 'cred.uid != 1000' in io
assert 'generation.find_first_not_of("0123456789")' in io
assert 'memcmp(data, generation.data(), generation.size())' in io
assert 'micMuted = true' in audio and 'if (allowed && record == null)' in audio
assert 'TYPE_BUILTIN_SPEAKER' in audio and 'TYPE_BUILTIN_MIC' in audio
assert 'pcm_prepare(toHost)' in io and 'pcm_stop(fromHost)' in io
assert 'queued.size() == 4' in io and 'queued.pop_front()' in io
assert 'catch (SocketTimeoutException waitingForHost)' in audio
assert 'PCM Capture' not in audio  # Android side never opens ALSA or mixer nodes.
assert '/dev/' not in audio
assert 'FOREGROUND_SERVICE_TYPE_MICROPHONE' in service
assert activity.count('boolean dispatchKeyEvent(KeyEvent event)') == 1
assert 'case "pointer": case "typing": navigate("session")' in activity
assert 'configfs:file { write append }' in policy
assert 'audio_device:chr_file' in policy
print('Phase B socket, microphone, routing and confinement contracts passed')
