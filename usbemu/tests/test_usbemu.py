#!/usr/bin/env python3
"""Port of UsbMode image/virtual-drive/helper cases to the R1 lease broker.

Runs the production POSIX shell functions. Configfs, HAL, properties and
formatters are file-backed fakes in a temporary directory; no device access.
"""
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'r1-usbemu.sh'
TEST_SHELL = os.environ.get('USBEMU_TEST_SHELL') or shutil.which('mksh') or 'sh'

MOCK = r'''#!/usr/bin/env python3
import os, pathlib, sys, time
p = pathlib.Path(os.environ['MOCK_PROPS'])
a = sys.argv[1:]
command = pathlib.Path(sys.argv[0]).name
if command == 'getprop':
    path = p / a[0]
    print(path.read_text() if path.exists() else '')
elif command == 'setprop':
    (p / a[0]).write_text(a[1])
    if a[0] == 'sys.r1.usbemu.functions':
        value = a[1]
        if value == 'none':
            (p/'revocation_at_release').write_text((p/'sys.r1.usbemu.st.generation').read_text())
        hal = 'idle' if value == 'none' else value.split(':')[0] + (' failed' if (p/'fail_bind').exists() else ' applied')
        if not (p/'timeout').exists(): (p/'vendor.r1.usbemu.state').write_text(hal)
elif command == 'mkdir':
    subprocess = __import__('subprocess')
    rc = subprocess.run(['/bin/mkdir'] + a).returncode
    if rc == 0 and a[-1].endswith('/mass_storage.usbemu0'):
        lun = pathlib.Path(a[-1])/'lun.0'; lun.mkdir()
        for f in ('cdrom','ro','removable','file','forced_eject'): (lun/f).touch()
    if rc == 0 and a[-1].endswith('/uac2.usbemu0'):
        function = pathlib.Path(a[-1])
        for f in ('p_mute_present','c_mute_present','p_volume_present','c_volume_present',
                  'p_volume_min','p_volume_max','p_volume_res','c_volume_min','c_volume_max','c_volume_res','c_sync','function_name'):
            (function/f).touch()
    if rc == 0 and (p/'fail_hid_setup').exists() and a[-1].endswith('/hid.usbemu1'):
        sys.exit(1)
    sys.exit(rc)
elif command == 'rmdir':
    path = pathlib.Path(a[-1])
    if path.name == 'mass_storage.usbemu0':
        if (p/'busy_remove').exists(): sys.exit(1)
        lun=path/'lun.0'
        if lun.exists():
            for f in lun.iterdir(): f.unlink()
            lun.rmdir()
    if path.name.startswith(('hid.usbemu','uac2.usbemu')):
        for f in path.iterdir(): f.unlink()
    try: path.rmdir()
    except OSError: sys.exit(1)
elif command == 'chown':
    pass
elif command == 'fsync':
    for path in a:
        fd=os.open(path,os.O_RDONLY);os.fsync(fd);os.close(fd)
elif command == 'stat' and a[:1] == ['-f']:
    # The folder's filesystem in 4 KiB blocks: free, block size, all
    print(('262144' if (p/'no_space').exists() else '15000000') + ':4096:16777216')
elif command == 'stat':
    os.execv('/usr/bin/stat', ['stat'] + a)
elif command in ('newfs_msdos','mkfs.exfat'):
    (p/'formatter_args').write_text('\n'.join([command]+a))
    if (p/'cancel_format').exists():
        token=(p/'sys.r1.usbemu.req.run').read_text().split(':')[0]
        (p/'sys.r1.usbemu.req.cancel').write_text(token)
        time.sleep(2)
    if (p/'fail_format').exists(): sys.exit(1)
    with open(a[-1],'r+b', buffering=0) as f: f.write(b'FAKE FILESYSTEM')
else: raise RuntimeError(command)
'''


class Broker(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = Path(__file__).parent / '.scratch'
        cls.scratch.mkdir(exist_ok=True)

    @classmethod
    def tearDownClass(cls):
        cls.scratch.rmdir()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='r1-usbemu-test-', dir=self.scratch)
        self.root = Path(self.temp.name).resolve()
        for name in ('state', 'ipc/requests', 'ipc/status', 'media/USB Images', 'functions', 'props', 'bin', 'loops'):
            (self.root/name).mkdir(parents=True, exist_ok=True)
        self.folder = self.root/'media/USB Images'
        self.props = self.root/'props'
        (self.root/'mountinfo').write_text('1 0 0:1 / / rw - rootfs rootfs rw\n')
        mock = self.root/'bin/mock'; mock.write_text(MOCK); mock.chmod(0o755)
        for tool in ('getprop','setprop','mkdir','rmdir','chown','fsync','stat','newfs_msdos','mkfs.exfat'):
            (self.root/'bin'/tool).symlink_to(mock)
        self.env = dict(os.environ, STATE=str(self.root/'state'), IPC=str(self.root/'ipc'),
                        MEDIA=str(self.root/'media'), FUNCTIONS=str(self.root/'functions'),
                        MOUNTS=str(self.root/'mountinfo'), LOOPS=str(self.root/'loops'),
                        R1_USBEMU_SOURCE_ONLY='1', MOCK_PROPS=str(self.props),
                        PATH=str(self.root/'bin')+':'+os.environ['PATH'])
        self.sequence = 100

    def tearDown(self): self.temp.cleanup()

    def shell(self, source, check=True):
        r = subprocess.run([TEST_SHELL,'-c','. "$1"; '+source, 'test', str(HELPER)],
                           env=self.env, text=True, capture_output=True, timeout=35)
        if check and r.returncode: self.fail(r.stderr + '\n' + r.stdout)
        return r

    def prop(self, name, value=None):
        path = self.props/('sys.r1.usbemu.'+name)
        if value is not None: path.write_text(value)
        return path.read_text() if path.exists() else ''

    def image(self, name='disc.iso', size=1048576):
        path = self.folder/name
        with path.open('wb') as f: f.truncate(size)
        return path

    def request(self, op, path=None, ro=0, size=1, fs='fat32', force=1, folder=None):
        self.sequence += 1
        token=str(self.sequence)
        st=path.stat() if path else None
        stamp=f'{st.st_dev}:{st.st_ino}:{st.st_size}' if st else ''
        fields=[str(folder or self.folder), path.name if path else 'blank.img', str(ro), stamp, str(size), fs, str(force), '1']
        (self.root/'ipc/requests'/token).write_text('\n'.join(fields)+'\n')
        self.prop('req.run',token+':'+op)
        self.shell('main run')
        return self.prop('st.result'), self.prop('st.error')

    def stop(self):
        self.sequence += 1
        self.prop('req.stop',str(self.sequence)); self.shell('main stop')

    def test_source_image_name_cases(self):
        good=['Alpine Linux.iSo','other.IMG','A-1_2.img','café.iso']
        bad=['../outside.iso','/tmp/out.iso','sub/file.iso','bad..iso','.hidden.iso','bad\tname.iso','bad\nname.iso','bad.iso.exe','bad.iso ','a'*252+'.iso']
        for name in good+bad:
            self.env['CANDIDATE']=name
            r=self.shell('image_name "$CANDIDATE"',check=False)
            self.assertEqual(r.returncode == 0, name in good, name)

    def test_source_cd_size_boundaries(self):
        for size, ok in [(512,False),(614400,True),(614401,False),(1048576,True),(2359293952,True),(2359296000,False)]:
            self.assertEqual(self.shell(f'cd_size {size}',check=False).returncode == 0, ok, size)

    def test_iso_readonly_start_swap_stop(self):
        one=self.image(); result,error=self.request('start',one)
        self.assertTrue(result.endswith(':done'),error)
        self.assertEqual(self.prop('st.session'),'cd')
        lun=self.root/'functions/mass_storage.usbemu0/lun.0'
        self.assertEqual((lun/'ro').read_text().strip(),'1')
        self.assertEqual((lun/'cdrom').read_text().strip(),'1')
        self.assertRegex((lun/'file').read_text().strip(),r'^/proc/[0-9]+/fd/9$')
        two=self.image('rescue.ISO'); lease=self.prop('functions')
        result,error=self.request('swap',two)
        self.assertTrue(result.endswith(':done'),error)
        self.assertEqual((lun/'forced_eject').read_text().strip(),'1')
        self.assertEqual(self.prop('functions'),lease)
        self.assertEqual((self.root/'ipc/status/session').read_text().splitlines()[1],'rescue.ISO')
        self.stop(); self.stop()
        self.assertEqual(self.prop('st.session'),'idle')
        self.assertFalse(lun.parent.exists())
        self.assertFalse((self.root/'state/journal').exists())

    def test_img_writable_and_readonly(self):
        for ro in (0,1):
            result,error=self.request('start', self.image('transfer.img'),ro=ro)
            self.assertTrue(result.endswith(':done'),error)
            lun=self.root/'functions/mass_storage.usbemu0/lun.0'
            self.assertEqual((lun/'cdrom').read_text().strip(),'0')
            self.assertEqual((lun/'ro').read_text().strip(),str(ro))
            self.stop()

    def test_failed_bind_rolls_back(self):
        (self.props/'fail_bind').touch()
        result,error=self.request('start',self.image())
        self.assertTrue(result.endswith(':error'))
        self.assertEqual(error,'hal_failed')
        self.assertEqual(self.prop('functions'),'none')
        self.assertFalse((self.root/'functions/mass_storage.usbemu0').exists())

    def test_bad_stamp_and_link(self):
        image=self.image()
        self.env.update(folder=str(self.folder),name=image.name,expected='0:0:1048576',ro='0')
        result=self.shell('validate_folder && open_image',check=False)
        self.assertNotEqual(result.returncode,0)
        target=self.root/'outside.iso'; target.write_bytes(b'x'*1048576)
        image.unlink(); image.symlink_to(target)
        result,error=self.request('start',image)
        self.assertTrue(result.endswith(':error'));self.assertEqual(error,'image')
        self.assertFalse((self.root/'functions/mass_storage.usbemu0').exists())

    def test_folder_symlink_and_outside(self):
        outside=self.root/'outside';outside.mkdir()
        link=self.root/'media/link';link.symlink_to(outside)
        for folder in (outside,link):
            result,error=self.request('scan',folder=folder)
            self.assertTrue(result.endswith(':error'));self.assertEqual(error,'folder')

    def test_mount_and_loop_rejected(self):
        image=self.image()
        escaped=str(self.folder).replace(' ',r'\040')
        with (self.root/'mountinfo').open('a') as f: f.write(f'2 1 0:1 / {escaped} rw - ext4 /fake rw\n')
        result,error=self.request('start',image)
        self.assertTrue(result.endswith(':error'));self.assertEqual(error,'mounted')
        (self.root/'mountinfo').write_text('')
        loop=self.root/'loops/loop0/loop';loop.mkdir(parents=True)
        (loop/'backing_file').write_text(str(image)+'\n')
        result,error=self.request('start',image)
        self.assertTrue(result.endswith(':error'));self.assertEqual(error,'mounted')

    def test_unused_loop_is_allowed(self):
        loop=self.root/'loops/loop0/loop';loop.mkdir(parents=True)
        (loop/'backing_file').write_text('')
        result,error=self.request('start',self.image())
        self.assertTrue(result.endswith(':done'),error)

    def test_bind_mounted_elsewhere_is_rejected(self):
        image=self.image()
        escaped=str(image).replace(' ',r'\040')
        (self.root/'mountinfo').write_text(f'2 1 0:1 {escaped} /elsewhere rw - ext4 /fake rw\n')
        result,error=self.request('start',image)
        self.assertTrue(result.endswith(':error'));self.assertEqual(error,'mounted')

    def test_hardlink_refused(self):
        image=self.image();os.link(image,self.folder/'other.iso')
        result,error=self.request('start',image)
        self.assertTrue(result.endswith(':error'));self.assertEqual(error,'image')

    def test_creation_sparse_and_no_clobber(self):
        for filesystem in ('fat32','exfat'):
            result,error=self.request('create',fs=filesystem)
            self.assertTrue(result.endswith(':done'),error)
            image=self.folder/'blank.img'
            self.assertEqual(image.stat().st_size,1073741824)
            self.assertLess(image.stat().st_blocks*512,1048576)
            args=(self.props/'formatter_args').read_text()
            self.assertIn('/proc/',args);self.assertNotIn('/dev/',args)
            if filesystem == 'fat32':
                for flag in ('-S','-s','-h','-u','-o'): self.assertIn(flag,args)
            result,error=self.request('create',fs=filesystem)
            self.assertTrue(result.endswith(':error'));self.assertEqual(error,'exists')
            with image.open('rb') as stream: self.assertEqual(stream.read(15),b'FAKE FILESYSTEM')
            image.unlink()

    def test_four_gib_creation_does_not_wrap_mksh_arithmetic(self):
        result,error=self.request('create',size=4)
        self.assertTrue(result.endswith(':done'),error)
        self.assertEqual((self.folder/'blank.img').stat().st_size,4294967296)
        args=(self.props/'formatter_args').read_text().splitlines()
        self.assertEqual(args[args.index('-s')+1],'8388608')

    def test_creation_space_and_size(self):
        (self.props/'no_space').touch()
        result,error=self.request('create')
        self.assertTrue(result.endswith(':error'));self.assertEqual(error,'space')
        (self.props/'no_space').unlink()
        for size in ('0','01','-1','17','1;id'):
            result,error=self.request('create',size=size)
            self.assertTrue(result.endswith(':error'));self.assertEqual(error,'size')
        self.assertEqual(list(self.folder.iterdir()),[])

    def test_format_failure_and_cancel_cleanup(self):
        for failure,expected in [('fail_format','format'),('cancel_format','cancelled')]:
            (self.props/failure).touch()
            result,error=self.request('create')
            self.assertEqual(error,expected)
            self.assertEqual(list(self.folder.iterdir()),[])
            self.assertFalse((self.root/'state/journal').exists())
            (self.props/failure).unlink()

    def test_recover_interrupted_create(self):
        self.env.update(folder=str(self.folder),name='blank.img',token='222')
        self.shell('journal create; : >"$folder/.r1-usbemu-222.creating"; identity "$folder/.r1-usbemu-222.creating" >"$STATE/journal/inode"; truncate -s 1073741824 "$folder/.r1-usbemu-222.creating"')
        self.shell('main boot')
        self.assertEqual(list(self.folder.iterdir()),[])
        self.shell('main boot')
        self.assertFalse((self.root/'state/journal').exists())

    def test_recovery_wont_delete_replacement(self):
        self.env.update(folder=str(self.folder),name='blank.img',token='222')
        self.shell('journal create; printf "0:0\\n" >"$STATE/journal/inode"')
        partial=self.folder/'.r1-usbemu-222.creating';partial.write_text('unowned')
        self.shell('main boot')
        self.assertEqual(partial.read_text(),'unowned')
        self.assertEqual(self.prop('st.error'),'recovery')
        self.assertTrue((self.root/'state/journal').exists())

    def test_boot_removes_only_stale_emulator_functions(self):
        (self.root/'functions/hid.usbemu0').mkdir()
        (self.root/'functions/acm.android').mkdir()
        self.shell('main boot')
        self.assertFalse((self.root/'functions/hid.usbemu0').exists())
        self.assertTrue((self.root/'functions/acm.android').exists())

    def test_late_hal_failure_releases_session(self):
        self.request('start',self.image())
        generation=self.prop('functions').split(':')[0]
        (self.props/'vendor.r1.usbemu.state').write_text(generation+' failed')
        self.shell('main check')
        self.assertEqual(self.prop('st.session'),'idle')
        self.assertEqual(self.prop('st.error'),'hal_failed')
        self.assertEqual(self.prop('functions'),'none')

    def test_killed_worker_recovers_without_replay(self):
        self.env.update(folder=str(self.folder),name='disc.iso',token='333')
        self.shell('journal start; mkdir "$F"')
        self.shell('main recover')
        self.assertEqual(self.prop('st.result'),'333:error')
        self.assertEqual(self.prop('st.error'),'interrupted')
        self.assertFalse((self.root/'functions/mass_storage.usbemu0').exists())
        self.shell('main recover')

    def test_timeout_keeps_journal_until_stop_can_finish(self):
        (self.props/'timeout').touch()
        result,error=self.request('start',self.image())
        self.assertTrue(result.endswith(':error'));self.assertEqual(error,'recovery')
        self.assertTrue((self.root/'state/journal').exists())
        (self.props/'timeout').unlink();self.stop()
        self.assertEqual(self.prop('st.session'),'idle')
        self.assertFalse((self.root/'state/journal').exists())

    def test_generation_increases(self):
        image=self.image()
        self.request('start',image);first=int(self.prop('functions').split(':')[0]);self.stop()
        self.request('start',image);second=int(self.prop('functions').split(':')[0])
        self.assertGreater(second,first)

    def test_scan_filters_images(self):
        self.image();self.image('drive.IMG');self.image('short.iso',512);self.image('bad.qcow2')
        result,error=self.request('scan')
        self.assertTrue(result.endswith(':done'),error)
        inventory=(self.root/'ipc/status/inventory').read_text()
        self.assertIn('disc.iso',inventory);self.assertIn('drive.IMG',inventory)
        self.assertNotIn('short.iso',inventory);self.assertNotIn('bad.qcow2',inventory)
        self.assertFalse((self.root/'functions/mass_storage.usbemu0').exists())

    def test_sound_stereo_directions_feature_units_and_stop(self):
        result,error=self.request('sound',folder=self.root/'missing')
        self.assertTrue(result.endswith(':done'),error)
        self.assertEqual(self.prop('st.session'),'sound')
        self.assertEqual(self.prop('functions'),'1:uac2.usbemu0')
        self.assertEqual(self.prop('st.generation'),'1')
        audio=self.root/'functions/uac2.usbemu0'
        for direction in ('p','c'):
            for key,value in [('chmask','3'),('srate','48000'),('ssize','2'),
                              ('volume_present','1'),('mute_present','1'),
                              ('volume_min','-24576'),('volume_max','0'),('volume_res','256')]:
                self.assertEqual((audio/(direction+'_'+key)).read_text().strip(),value)
        self.assertEqual((audio/'c_sync').read_text().strip(),'async')
        self.assertEqual((audio/'function_name').read_text().strip(),'R1 USB Audio')
        self.stop()
        self.assertEqual(self.prop('st.generation'),'0')
        self.assertEqual((self.props/'revocation_at_release').read_text(),'0')
        self.assertEqual(list((self.root/'functions').iterdir()),[])

    def test_input_reports_no_out_endpoints_and_stop(self):
        result,error=self.request('input')
        self.assertTrue(result.endswith(':done'),error)
        self.assertEqual(self.prop('st.session'),'input')
        self.assertEqual(self.prop('functions'),'1:hid.usbemu0,hid.usbemu1')
        keyboard=self.root/'functions/hid.usbemu0';mouse=self.root/'functions/hid.usbemu1'
        for function,length,subclass,protocol in [(keyboard,'8','1','1'),(mouse,'4','0','0')]:
            for key,value in [('report_length',length),('subclass',subclass),('protocol',protocol),('no_out_endpoint','1')]:
                self.assertEqual((function/key).read_text().strip(),value)
        # Exact andr36oid keyboard and wheel-mouse descriptors, no report IDs.
        self.assertEqual((keyboard/'report_desc').read_bytes(),bytes.fromhex(
            '05010906a101050719e029e71500250175019508810295017508810195057501050819012905910295017503910195067508150025730507190029738100c0'))
        self.assertEqual((mouse/'report_desc').read_bytes(),bytes.fromhex(
            '05010902a1010901a1000509190129031500250195037501810295017505810105010930093109381581257f750895038106c0c0'))
        self.stop()
        self.assertEqual(list((self.root/'functions').iterdir()),[])

    def test_composite_cd_swap_preserves_input_and_generation(self):
        one=self.image();two=self.image('second.iso')
        result,error=self.request('start_input',one)
        self.assertTrue(result.endswith(':done'),error)
        functions=self.prop('functions')
        self.assertEqual(functions,'1:mass_storage.usbemu0,hid.usbemu0,hid.usbemu1')
        self.assertEqual(self.prop('st.session'),'cd_input')
        result,error=self.request('swap',two)
        self.assertTrue(result.endswith(':done'),error)
        self.assertEqual(self.prop('functions'),functions)
        self.assertEqual(self.prop('st.session'),'cd_input')
        self.assertTrue((self.root/'functions/hid.usbemu1').exists())
        self.stop()
        self.assertEqual(list((self.root/'functions').iterdir()),[])

    def test_drive_composite_and_budget(self):
        result,error=self.request('start_input',self.image('drive.img'))
        self.assertTrue(result.endswith(':done'),error)
        self.assertEqual(self.prop('st.session'),'drive_input')
        for mode,counts in [('drive','2:2'),('cd','2:2'),('input','3:1'),('sound','4:2'),
                            ('drive_input','4:2'),('cd_input','4:2')]:
            self.assertEqual(self.shell(f'mode={mode}; endpoint_budget && printf "%s:%s" "$ep_in" "$ep_out"').stdout,counts)
        self.assertNotEqual(self.shell('mode=arbitrary; endpoint_budget',check=False).returncode,0)

    def test_endpoint_limits_reject_each_exhausted_direction(self):
        for ins,outs,ok in [(7,7,True),(8,1,False),(1,8,False),(8,8,False)]:
            result=self.shell(f'ep_in={ins}; ep_out={outs}; error=; check_endpoints; rc=$?; printf "%s:%s" "$rc" "$error"')
            self.assertEqual(result.stdout,'0:' if ok else '1:endpoints')

    def test_partial_hid_setup_rolls_back_all_functions(self):
        (self.props/'fail_hid_setup').touch()
        result,error=self.request('start_input',self.image())
        self.assertTrue(result.endswith(':error'))
        self.assertEqual(list((self.root/'functions').iterdir()),[])
        self.assertFalse((self.root/'state/journal').exists())
        self.assertEqual(self.prop('functions'),'none')

    def test_sound_failed_lease_and_late_failure_cleanup(self):
        (self.props/'fail_bind').touch()
        result,error=self.request('sound')
        self.assertTrue(result.endswith(':error'));self.assertEqual(error,'hal_failed')
        self.assertEqual(list((self.root/'functions').iterdir()),[])
        (self.props/'fail_bind').unlink()
        self.request('sound')
        generation=self.prop('st.generation')
        (self.props/'vendor.r1.usbemu.state').write_text(generation+' failed')
        self.shell('main check')
        self.assertEqual(self.prop('st.session'),'idle')
        self.assertEqual(self.prop('st.generation'),'0')
        self.assertEqual((self.props/'revocation_at_release').read_text(),'0')
        self.assertEqual(list((self.root/'functions').iterdir()),[])

    def test_active_input_cannot_be_replaced_by_sound(self):
        self.request('input');functions=self.prop('functions')
        result,error=self.request('sound')
        self.assertTrue(result.endswith(':error'));self.assertEqual(error,'active')
        self.assertEqual(self.prop('functions'),functions)
        self.assertEqual(self.prop('st.session'),'input')

    def test_boot_cleans_composite_journal_without_replaying(self):
        self.env.update(folder=str(self.folder),name='disc.iso',token='333')
        self.shell('journal start; mkdir "$F"; setup_hid; setup_sound')
        self.shell('main boot')
        self.assertEqual(list((self.root/'functions').iterdir()),[])
        self.assertEqual(self.prop('st.generation'),'0')
        self.assertEqual(self.prop('st.session'),'idle')


if __name__ == '__main__':
    print('Production helper test shell:', TEST_SHELL, flush=True)
    unittest.main(verbosity=2)
