import os
import signal
import subprocess
import sys
import threading

if sys.version_info < (3, 11):
    sys.exit(f"A script needs Python 3.11 or newer; python3 is {sys.version.split()[0]}.")

script = subprocess.Popen(
    [sys.executable, "-P", "-u", sys.argv[1]], stdin=subprocess.DEVNULL, start_new_session=True
)
# After the spawn, so the script keeps the default handlers. A signal sent to the server's
# process group must not end the launcher while its script lives on.
for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
    signal.signal(signum, signal.SIG_IGN)


def kill_script_group() -> None:
    try:
        os.killpg(script.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass


def kill_once_the_server_lets_go() -> None:
    while os.read(sys.stdin.fileno(), 1024):
        pass
    kill_script_group()


threading.Thread(target=kill_once_the_server_lets_go, daemon=True).start()
exit_code = script.wait()
kill_script_group()
# Not sys.exit: interpreter shutdown aborts on the thread still blocked reading stdin.
os._exit(0 if exit_code == 0 else 1)
