#!/usr/bin/env python3
"""Build with a JDK and Android build-tools/platform, without Gradle dependencies."""
import os
from pathlib import Path
import secrets
import subprocess
import zipfile

root = Path(__file__).resolve().parent
java = Path(os.environ['SAVE_OBSIDIAN_JDK'])
tools = Path(os.environ['SAVE_OBSIDIAN_TOOLS'])
platform = Path(os.environ['SAVE_OBSIDIAN_PLATFORM'])
build = root / 'build'
classes = build / 'classes'
classes.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, JAVA_HOME=str(java))
env['PATH'] = str(java / 'bin') + os.pathsep + env['PATH']

def run(*args):
    subprocess.run([str(x) for x in args], cwd=root, env=env, check=True)

run(java / 'bin/javac', '-encoding', 'UTF-8', '-d', classes,
    root / 'src/me/venkatesh/saveobsidian/Capture.java', root / 'src/me/venkatesh/saveobsidian/AppendCheck.java',
    root / 'src/me/venkatesh/saveobsidian/InboxFile.java', root / 'src/me/venkatesh/saveobsidian/VerifiedEdit.java', root / 'src/me/venkatesh/saveobsidian/Enrich.java', root / 'src/me/venkatesh/saveobsidian/Index.java', root / 'src/me/venkatesh/saveobsidian/Vault.java', root / 'src/me/venkatesh/saveobsidian/Layout.java', root / 'src/me/venkatesh/saveobsidian/Lane.java', root / 'src/me/venkatesh/saveobsidian/Feed.java', root / 'src/me/venkatesh/saveobsidian/NoteEdit.java', root / 'src/me/venkatesh/saveobsidian/IngestText.java', root / 'tests/CaptureTest.java')
run(java / 'bin/java', '-cp', classes, 'CaptureTest')
run(tools / 'aapt2', 'compile', '--dir', root / 'res', '-o', build / 'resources.zip')
run(tools / 'aapt2', 'link', '-o', build / 'unsigned.apk', '-I', platform / 'android.jar',
    '--manifest', root / 'AndroidManifest.xml', '--min-sdk-version', '26', '--target-sdk-version', '35',
    '--java', build / 'generated', build / 'resources.zip')
run(java / 'bin/javac', '-encoding', 'UTF-8', '--release', '8', '-classpath', platform / 'android.jar',
    '-d', classes, *sorted((root / 'src').rglob('*.java')), *sorted((build / 'generated').rglob('*.java')))
run(java / 'bin/jar', 'cf', build / 'app.jar', '-C', classes, 'me')
run(tools / 'd8', '--min-api', '26', '--lib', platform / 'android.jar', '--output', build, build / 'app.jar')
with zipfile.ZipFile(build / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
    apk.write(build / 'classes.dex', 'classes.dex')
run(tools / 'zipalign', '-f', '4', build / 'unsigned.apk', build / 'aligned.apk')

private = Path.home() / '.local/share/save-to-obsidian-signing'
private.mkdir(mode=0o700, parents=True, exist_ok=True)
private.chmod(0o700)
password = private / 'password'
key = private / 'release.jks'
if not password.exists():
    password.write_text(secrets.token_urlsafe(32))
    password.chmod(0o600)
if not key.exists():
    run(java / 'bin/keytool', '-genkeypair', '-keystore', key, '-storepass:file', password,
        '-keypass:file', password, '-alias', 'release', '-keyalg', 'RSA', '-keysize', '3072',
        '-validity', '10000', '-dname', 'CN=Save to Obsidian Personal Build')
    key.chmod(0o600)
run(tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'release',
    '--ks-pass', 'file:' + str(password),
    '--out', build / 'Save-to-Obsidian.apk', build / 'aligned.apk')
run(tools / 'apksigner', 'verify', '--verbose', build / 'Save-to-Obsidian.apk')
run(tools / 'aapt2', 'dump', 'badging', build / 'Save-to-Obsidian.apk')
print('APK:', build / 'Save-to-Obsidian.apk')
