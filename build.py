#!/usr/bin/env python3
"""Run JVM checks with a JDK, without Gradle dependencies."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent
jdk_path = os.environ.get('SAVE_OBSIDIAN_JDK') or os.environ.get('JAVA_HOME') or '/opt/homebrew/opt/openjdk'
java = Path(jdk_path)
build = root / 'build'
classes = build / 'classes'
classes.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, JAVA_HOME=str(java))
env['PATH'] = str(java / 'bin') + os.pathsep + env.get('PATH', '')

def run(*args):
    subprocess.run([str(x) for x in args], cwd=root, env=env, check=True)

jsoup = root / 'lib/jsoup-1.18.3.jar'
testjson = root / 'lib/json-20240303.jar'
run(java / 'bin/javac', '-encoding', 'UTF-8', '-classpath', str(jsoup) + os.pathsep + str(testjson), '-d', classes,
    root / 'src/me/venkatesh/saveobsidian/ModelCatalog.java', root / 'src/me/venkatesh/saveobsidian/DirectText.java', root / 'src/me/venkatesh/saveobsidian/Extract.java', root / 'src/me/venkatesh/saveobsidian/Instagram.java', root / 'src/me/venkatesh/saveobsidian/Transcript.java', root / 'src/me/venkatesh/saveobsidian/JsonFix.java', root / 'src/me/venkatesh/saveobsidian/Net.java',
    root / 'src/me/venkatesh/saveobsidian/Capture.java', root / 'src/me/venkatesh/saveobsidian/AppendCheck.java',
    root / 'src/me/venkatesh/saveobsidian/InboxFile.java', root / 'src/me/venkatesh/saveobsidian/VerifiedEdit.java', root / 'src/me/venkatesh/saveobsidian/VaultLock.java', root / 'src/me/venkatesh/saveobsidian/Cancel.java', root / 'src/me/venkatesh/saveobsidian/RunGate.java', root / 'src/me/venkatesh/saveobsidian/Limits.java', root / 'src/me/venkatesh/saveobsidian/Failures.java', root / 'src/me/venkatesh/saveobsidian/Enrich.java', root / 'src/me/venkatesh/saveobsidian/Index.java', root / 'src/me/venkatesh/saveobsidian/QueryVectors.java', root / 'src/me/venkatesh/saveobsidian/Vault.java', root / 'src/me/venkatesh/saveobsidian/Layout.java', root / 'src/me/venkatesh/saveobsidian/Lane.java', root / 'src/me/venkatesh/saveobsidian/Feed.java', root / 'src/me/venkatesh/saveobsidian/NoteEdit.java', root / 'src/me/venkatesh/saveobsidian/Cites.java', root / 'src/me/venkatesh/saveobsidian/Cards.java', root / 'src/me/venkatesh/saveobsidian/Sched.java', root / 'src/me/venkatesh/saveobsidian/Spark.java', root / 'src/me/venkatesh/saveobsidian/Study.java', root / 'src/me/venkatesh/saveobsidian/CardPrompt.java', root / 'src/me/venkatesh/saveobsidian/IngestText.java', root / 'src/me/venkatesh/saveobsidian/Truncate.java', root / 'src/me/venkatesh/saveobsidian/Validate.java', root / 'src/me/venkatesh/saveobsidian/Tool.java', root / 'src/me/venkatesh/saveobsidian/Agent.java', root / 'src/me/venkatesh/saveobsidian/ScoutState.java', root / 'src/me/venkatesh/saveobsidian/ScoutChanges.java', root / 'src/me/venkatesh/saveobsidian/ScoutEvidence.java', root / 'src/me/venkatesh/saveobsidian/ScoutMarkdown.java', root / 'src/me/venkatesh/saveobsidian/Session.java', root / 'src/me/venkatesh/saveobsidian/SessionUsage.java', root / 'src/me/venkatesh/saveobsidian/Compact.java', root / 'src/me/venkatesh/saveobsidian/OpenRouter.java', root / 'src/me/venkatesh/saveobsidian/Sse.java', root / 'src/me/venkatesh/saveobsidian/VaultTools.java', root / 'src/me/venkatesh/saveobsidian/Skills.java', root / 'src/me/venkatesh/saveobsidian/Mcp.java', root / 'src/me/venkatesh/saveobsidian/Chat.java', root / 'tests/CaptureTest.java', root / 'tests/AgentTest.java', root / 'tests/SessionUsageTest.java', root / 'tests/VaultToolsTest.java', root / 'tests/McpTest.java', root / 'tests/ScoutTest.java', root / 'tests/RetrievalTest.java')
run(java / 'bin/java', '-cp', str(classes) + os.pathsep + str(jsoup) + os.pathsep + str(testjson), 'CaptureTest')
print('JVM checks passed. APK is built by Gradle: ./gradlew assembleRelease')
