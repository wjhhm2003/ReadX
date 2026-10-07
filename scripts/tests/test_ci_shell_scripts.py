"""Execute CI Bash entry points with fake adb/Gradle, never a real device."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class CiShellScriptsTest(unittest.TestCase):
    def setUp(self):
        candidates = [Path('/usr/bin/bash'), Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Git/bin/bash.exe']
        self.bash = next((str(p) for p in candidates if p.is_file()), None) or shutil.which('bash')
        self.assertIsNotNone(self.bash, 'Bash is required to verify CI entry points')
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'scripts').mkdir()
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        for name in ('ci-device-tests.sh', 'ci-preview-smoke.sh'):
            shutil.copyfile(ROOT / 'scripts' / name, self.root / 'scripts' / name)
        self.write_executable(self.root / 'gradlew', '#!/usr/bin/env bash\nprintf "%s\\n" "$*" >> commands.txt\nexit "$GRADLE_STATUS"\n')
        self.write_executable(self.bin / 'adb', '''#!/usr/bin/env bash
printf "%s\\n" "$*" >> commands.txt
case "$*" in
  "shell am start"*) printf 'Status: %s\\n' "$LAUNCH_STATUS" ;;
  "shell pidof"*) echo 1234 ;;
  "logcat -b crash -d") if [ "$CRASH" = 1 ]; then echo 'Process: io.readx.app'; fi ;;
  *) : ;;
esac
''')
        self.env = {**os.environ, 'PATH': str(self.bin) + os.pathsep + os.environ.get('PATH', ''),
                    'MSYS_NO_PATHCONV': '1', 'MSYS2_ARG_CONV_EXCL': '*',
                    'GRADLE_STATUS': '0', 'LAUNCH_STATUS': 'ok', 'CRASH': '0'}

    def write_executable(self, path, text):
        path.write_text(text, encoding='utf-8', newline='\n')
        path.chmod(0o755)

    def run_script(self, name, **env):
        return subprocess.run([self.bash, 'scripts/' + name], cwd=self.root, env={**self.env, **env},
                              capture_output=True, text=True, timeout=20)

    def test_bash_syntax(self):
        for name in ('ci-device-tests.sh', 'ci-preview-smoke.sh'):
            result = subprocess.run([self.bash, '-n', 'scripts/' + name], cwd=self.root, capture_output=True, timeout=20)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_device_failure_is_preserved_and_screenshots_collected(self):
        result = self.run_script('ci-device-tests.sh', GRADLE_STATUS='7')
        self.assertEqual(7, result.returncode, result.stderr)
        commands = (self.root / 'commands.txt').read_text()
        self.assertIn('-PbundledOcr=false', commands)
        self.assertIn('connectedDebugAndroidTest', commands)
        self.assertIn('pull /sdcard/Android/data/io.readx.app/files/qa', commands)
        self.assertNotIn('pm clear', commands)

    def test_smoke_success_checks_launch_process_and_crash_buffer(self):
        result = self.run_script('ci-preview-smoke.sh')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.root / 'build/ci/reports/preview-launch.txt').is_file())
        commands = (self.root / 'commands.txt').read_text()
        self.assertIn('install -r build/ci/release/app-preview.apk', commands)
        self.assertIn('shell pidof io.readx.app', commands)
        self.assertIn('logcat -b crash -d', commands)

    def test_smoke_rejects_bad_launch_and_crash(self):
        for env in ({'LAUNCH_STATUS': 'error'}, {'CRASH': '1'}):
            with self.subTest(env=env):
                result = self.run_script('ci-preview-smoke.sh', **env)
                self.assertNotEqual(0, result.returncode)


if __name__ == '__main__':
    unittest.main()
