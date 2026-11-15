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

jsoup = root / 'lib/jsoup-1.18.3.jar'
testjson = root / 'lib/json-20240303.jar' # org.json for the JVM checks only; the phone has its own
run(java / 'bin/javac', '-encoding', 'UTF-8', '-classpath', str(jsoup) + os.pathsep + str(testjson), '-d', classes,
    root / 'src/me/venkatesh/saveobsidian/Extract.java', root / 'src/me/venkatesh/saveobsidian/Instagram.java', root / 'src/me/venkatesh/saveobsidian/Transcript.java', root / 'src/me/venkatesh/saveobsidian/JsonFix.java', root / 'src/me/venkatesh/saveobsidian/Net.java',
    root / 'src/me/venkatesh/saveobsidian/Capture.java', root / 'src/me/venkatesh/saveobsidian/AppendCheck.java',
    root / 'src/me/venkatesh/saveobsidian/InboxFile.java', root / 'src/me/venkatesh/saveobsidian/VerifiedEdit.java', root / 'src/me/venkatesh/saveobsidian/VaultLock.java', root / 'src/me/venkatesh/saveobsidian/Cancel.java', root / 'src/me/venkatesh/saveobsidian/RunGate.java', root / 'src/me/venkatesh/saveobsidian/Limits.java', root / 'src/me/venkatesh/saveobsidian/Failures.java', root / 'src/me/venkatesh/saveobsidian/Enrich.java', root / 'src/me/venkatesh/saveobsidian/Index.java', root / 'src/me/venkatesh/saveobsidian/Vault.java', root / 'src/me/venkatesh/saveobsidian/Layout.java', root / 'src/me/venkatesh/saveobsidian/Lane.java', root / 'src/me/venkatesh/saveobsidian/Feed.java', root / 'src/me/venkatesh/saveobsidian/NoteEdit.java', root / 'src/me/venkatesh/saveobsidian/Cites.java', root / 'src/me/venkatesh/saveobsidian/Cards.java', root / 'src/me/venkatesh/saveobsidian/Sched.java', root / 'src/me/venkatesh/saveobsidian/Spark.java', root / 'src/me/venkatesh/saveobsidian/Study.java', root / 'src/me/venkatesh/saveobsidian/CardPrompt.java', root / 'src/me/venkatesh/saveobsidian/IngestText.java', root / 'src/me/venkatesh/saveobsidian/Truncate.java', root / 'src/me/venkatesh/saveobsidian/Validate.java', root / 'src/me/venkatesh/saveobsidian/Tool.java', root / 'src/me/venkatesh/saveobsidian/Agent.java', root / 'src/me/venkatesh/saveobsidian/Session.java', root / 'src/me/venkatesh/saveobsidian/SessionUsage.java', root / 'src/me/venkatesh/saveobsidian/Compact.java', root / 'src/me/venkatesh/saveobsidian/OpenRouter.java', root / 'src/me/venkatesh/saveobsidian/VaultTools.java', root / 'src/me/venkatesh/saveobsidian/Skills.java', root / 'src/me/venkatesh/saveobsidian/Mcp.java', root / 'src/me/venkatesh/saveobsidian/Chat.java', root / 'tests/CaptureTest.java', root / 'tests/AgentTest.java', root / 'tests/SessionUsageTest.java', root / 'tests/VaultToolsTest.java', root / 'tests/McpTest.java')
run(java / 'bin/java', '-cp', str(classes) + os.pathsep + str(jsoup) + os.pathsep + str(testjson), 'CaptureTest')
if any((root / 'src').rglob('*.kt')):
    print('Kotlin sources present: the APK is built by Gradle now (./gradlew assembleRelease). build.py ran the JVM checks only.')
    raise SystemExit(0)
# the manifest has no package (Gradle takes it from namespace); aapt2 needs one
manifest = build / 'AndroidManifest.xml'
manifest.write_text((root / 'AndroidManifest.xml').read_text().replace('<manifest ', '<manifest package="me.venkatesh.saveobsidian" ', 1))
run(tools / 'aapt2', 'compile', '--dir', root / 'res', '-o', build / 'resources.zip')
run(tools / 'aapt2', 'link', '-o', build / 'unsigned.apk', '-I', platform / 'android.jar',
    '--manifest', manifest, '--min-sdk-version', '26', '--target-sdk-version', '35',
    '--version-code', '12', '--version-name', '1.9.0', # kept in step with build.gradle.kts
    '--java', build / 'generated', build / 'resources.zip')
run(java / 'bin/javac', '-encoding', 'UTF-8', '--release', '8', '-classpath', str(platform / 'android.jar') + os.pathsep + str(jsoup),
    '-d', classes, *sorted((root / 'src').rglob('*.java')), *sorted((build / 'generated').rglob('*.java')))
run(java / 'bin/jar', 'cf', build / 'app.jar', '-C', classes, 'me')
run(tools / 'd8', '--min-api', '26', '--lib', platform / 'android.jar', '--output', build, build / 'app.jar', jsoup)
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
