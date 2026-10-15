"""Isolated launcher integration checks. Run with Python 3 on Windows or Linux.

Windows uses a tiny Java fixture (JAVA_HOME must contain a JDK). Linux uses a
Python socket fixture, so the launcher can be tested without installing Java.
All scripts, logs and PID files are copied into a fresh directory under target.
"""
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[3]
WINDOWS = os.name == 'nt'
ARTIFACTS = ROOT / 'target' / 'launcher-tests'
ARTIFACTS.mkdir(parents=True, exist_ok=True)
WORK = Path(tempfile.mkdtemp(prefix='case with spaces ', dir=ARTIFACTS))
ENV = os.environ.copy()
ENV['DB_MCP_STARTUP_TIMEOUT_SEC'] = '15'
for name in ['DB_MCP_JAR_PATH', 'DB_MCP_EXPORT_PATH', 'DB_MCP_JAR_NAME', 'LAUNCHER_PROBE_MODE', 'JDK25']:
    ENV.pop(name, None)
for name in ['db-mcp.cmd', 'db-mcp.ps1', 'db-mcp']:
    (WORK / name).write_text((ROOT / name).read_text(), encoding='utf-8', newline='\n')
JAR = WORK / 'jar with spaces' / 'probe.jar'
JAR.parent.mkdir()
if WINDOWS:
    jdk = Path(ENV['JAVA_HOME']) / 'bin'
    classes = WORK / 'classes'
    classes.mkdir()
    subprocess.run([str(jdk / 'javac.exe'), '-d', str(classes),
                    str(ROOT / 'src/test/launchers/LauncherProbe.java')], check=True)
    subprocess.run([str(jdk / 'jar.exe'), '--create', '--file', str(JAR),
                    '--main-class', 'LauncherProbe', '-C', str(classes), '.'], check=True)
else:
    subprocess.run(['sh', '-n', str(ROOT / 'db-mcp')], check=True)
    assert b'\r\n' not in (ROOT / 'db-mcp').read_bytes(), 'Linux launcher must use LF'
    JAR.touch()
    java_home = WORK / 'fake-jdk'
    (java_home / 'bin').mkdir(parents=True)
    probe = java_home / 'probe.py'
    probe.write_text('''import os, socket, sys, time
port = int(os.environ['SERVER_PORT'])
with socket.socket() as server:
    try:
        server.bind(('127.0.0.1', port))
        server.listen()
    except OSError:
        print(f'Web server failed to start. Port {port} was already in use.', flush=True)
        sys.exit(1)
    if os.environ.get('LAUNCHER_PROBE_MODE') != 'quiet':
        time.sleep(1)
        print(f'Tomcat started on port {server.getsockname()[1]} (http)', flush=True)
        print('Started LauncherProbe in 1.0 seconds', flush=True)
    time.sleep(600)
''')
    java = java_home / 'bin/java'
    java.write_text('#!/bin/sh\nexec python3 "$(dirname -- "$0")/../probe.py" "$@"\n')
    java.chmod(0o755)
    ENV['JAVA_HOME'] = str(java_home)

DEFAULT_JAR = WORK / 'db-mcp.jar'
DEFAULT_JAR.write_bytes(JAR.read_bytes())


def run(action=None, jar=False, timeout=45):
    args = [] if action is None else [action]
    if jar:
        args.append(str(JAR))
    if WINDOWS:
        command = ['cmd.exe', '/d', '/c', 'call', str(WORK / 'db-mcp.cmd'), *args]
    else:
        command = ['sh', str(WORK / 'db-mcp'), *args]
    # A detached Windows child may inherit a captured pipe handle and keep
    # communicate() waiting for EOF after the launcher has already exited.
    capture = WORK / ('capture-' + str(time.time_ns()) + '.txt')
    with capture.open('wb') as stream:
        result = subprocess.run(command, env=ENV, cwd=WORK, stdin=subprocess.DEVNULL,
                                stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    output = capture.read_bytes().decode(errors='replace')
    print(f'{action or "default"}: exit={result.returncode}\n{output}', flush=True)
    return result.returncode, output


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def assert_ok(result, *messages):
    code, output = result
    assert code == 0, output
    for message in messages:
        assert message in output, (message, output)


try:
    ENV['SERVER_PORT'] = str(free_port())
    # Deployment uses the sibling JAR even with a stale target-path record.
    (WORK / 'run').mkdir()
    (WORK / 'run/db-mcp.jar-path').write_text(str(WORK / 'target/missing.jar'))
    assert_ok(run('restart'), 'service port: ' + ENV['SERVER_PORT'])
    assert Path((WORK / 'run/db-mcp.jar-path').read_text().strip()) == DEFAULT_JAR
    assert_ok(run('stop'), 'stopped')
    # Restart when stopped, including a spaced JAR path and delayed readiness.
    assert_ok(run('restart', jar=True), 'service port: ' + ENV['SERVER_PORT'])
    first_pid = (WORK / 'run/db-mcp.pid').read_text().strip()
    assert_ok(run('start'), 'already running', first_pid)
    # Invalid replacement must not stop the existing service.
    ENV['DB_MCP_JAR_PATH'] = str(WORK / 'missing.jar')
    code, output = run('restart')
    assert code != 0 and 'JAR not found' in output, output
    ENV.pop('DB_MCP_JAR_PATH')
    assert_ok(run('start'), 'already running', first_pid)
    if WINDOWS:
        assert_ok(run(), 'service port: ' + ENV['SERVER_PORT'])
    else:
        assert_ok(run('restart'), 'service port: ' + ENV['SERVER_PORT'])
    assert Path((WORK / 'run/db-mcp.jar-path').read_text().strip()) == DEFAULT_JAR
    assert (WORK / 'run/db-mcp.pid').read_text().strip() != first_pid
    assert_ok(run('stop'), 'stopped')
    assert_ok(run('stop'), 'not running')

    # A real occupied socket must report the actual owner's PID and directory.
    with socket.socket() as occupied:
        occupied.bind(('127.0.0.1', 0))
        occupied.listen()
        ENV['SERVER_PORT'] = str(occupied.getsockname()[1])
        code, output = run('start')
        assert code != 0, output
        for message in ['Port conflict: ' + ENV['SERVER_PORT'],
                        'Owner PID: ' + str(os.getpid()), 'Program directory:', str(Path(sys.executable).parent)]:
            assert message in output, (message, output)
        assert not (WORK / 'run/db-mcp.pid').exists()

    # A Java/JAR owner must report the application directory, not just the JRE.
    ENV['SERVER_PORT'] = str(free_port())
    java = Path(ENV['JAVA_HOME']) / 'bin' / ('java.exe' if WINDOWS else 'java')
    owner = subprocess.Popen([str(java), '-jar', str(JAR)], env=ENV,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             **({'creationflags': subprocess.CREATE_NO_WINDOW} if WINDOWS else {}))
    try:
        for _ in range(50):
            try:
                with socket.create_connection(('127.0.0.1', int(ENV['SERVER_PORT'])), timeout=0.1):
                    break
            except OSError:
                time.sleep(0.1)
        else:
            raise AssertionError('Conflict fixture did not start')
        code, output = run('start')
        assert code != 0, output
        for message in ['Owner PID: ' + str(owner.pid), 'Application JAR: ' + str(JAR),
                        'Program directory: ' + str(JAR.parent)]:
            assert message in output, (message, output)
    finally:
        owner.terminate()
        owner.wait(timeout=10)

    # Old successful log entries must not make an unready launch look ready.
    ENV['SERVER_PORT'] = str(free_port())
    ENV['LAUNCHER_PROBE_MODE'] = 'quiet'
    ENV['DB_MCP_STARTUP_TIMEOUT_SEC'] = '2'
    code, output = run('start')
    assert code != 0 and 'not ready after 2 seconds' in output, output
    assert (WORK / 'run/db-mcp.pid').exists()
    assert_ok(run('stop'), 'stopped')
    ENV.pop('LAUNCHER_PROBE_MODE')
    ENV['DB_MCP_STARTUP_TIMEOUT_SEC'] = '15'

    # The named JDK must take precedence over an unusable JAVA_HOME.
    actual_home = ENV['JAVA_HOME']
    ENV['JDK25'] = actual_home
    ENV['JAVA_HOME'] = str(WORK / 'invalid-default-jdk')
    assert_ok(run('start'), 'Java: ' + str(Path(actual_home) / 'bin' / ('java.exe' if WINDOWS else 'java')))
    named_pid = (WORK / 'run/db-mcp.pid').read_text().strip()
    ENV['JDK25'] = str(WORK / 'invalid-named-jdk')
    code, output = run('restart')
    assert code != 0 and 'Java executable not found for JDK25' in output, output
    assert (WORK / 'run/db-mcp.pid').read_text().strip() == named_pid
    ENV['JDK25'] = actual_home
    assert_ok(run('start'), 'already running', named_pid)
    assert_ok(run('stop'), 'stopped')
    ENV.pop('JDK25')
    ENV['JAVA_HOME'] = actual_home

    if not WINDOWS:
        # Use a real terminal to verify both the five-second pause and any key.
        import pty
        import select

        def terminal_pause(press_key):
            master, slave = pty.openpty()
            original = subprocess.check_output(['stty', '-g'], stdin=slave).strip()
            proc = subprocess.Popen(['sh', str(WORK / 'db-mcp'), 'stop'], env=ENV,
                                    stdin=slave, stdout=slave, stderr=slave, close_fds=True)
            output = b''
            deadline = time.monotonic() + 10
            while b'Press any key' not in output and time.monotonic() < deadline:
                if select.select([master], [], [], 0.2)[0]:
                    output += os.read(master, 4096)
            assert b'Press any key' in output, output
            start = time.monotonic()
            if press_key:
                time.sleep(0.2)
                os.write(master, b'x')
            proc.wait(timeout=8)
            elapsed = time.monotonic() - start
            restored = subprocess.check_output(['stty', '-g'], stdin=slave).strip()
            assert restored == original, (original, restored)
            os.close(master)
            os.close(slave)
            assert proc.returncode == 0
            assert elapsed < 2 if press_key else 4.5 <= elapsed <= 7, elapsed
            print(f'Terminal pause press_key={press_key}: {elapsed:.2f}s; terminal restored', flush=True)

        terminal_pause(False)
        terminal_pause(True)
    print('ALL LAUNCHER CHECKS PASSED: ' + str(WORK), flush=True)
finally:
    # Only the isolated copied script's own PID/JAR records are used for cleanup.
    run('stop')
