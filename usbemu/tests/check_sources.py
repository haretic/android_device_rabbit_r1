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
assert any(s.attrib.get(a+'foregroundServiceType') == 'specialUse' for s in services)
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
