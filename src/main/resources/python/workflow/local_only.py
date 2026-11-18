"""Run an existing trusted bridge with Python network operations disabled.

This is a reliability guard for prepared inference, not a security sandbox.
It blocks attempts rather than silently allowing download/cache fallback.
"""
import os
import runpy
import sys

attempted = False


def audit(event, args):
    global attempted
    if event in {"socket.connect", "socket.getaddrinfo", "socket.gethostbyname", "socket.gethostbyaddr", "socket.sendto"}:
        attempted = True
        raise RuntimeError("ATOLYE_LOCAL_ONLY: network access attempted during local inference")


def main():
    if len(sys.argv) < 2 or not os.path.isfile(sys.argv[1]):
        raise SystemExit("A prepared local bridge is required")
    bridge = os.path.abspath(sys.argv[1])
    sys.argv = sys.argv[1:]
    os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1", DO_NOT_TRACK="1")
    sys.addaudithook(audit)
    exit_code = 0
    try:
        runpy.run_path(bridge, run_name="__main__")
    except SystemExit as exc:
        exit_code = exc.code or 0
    if attempted:
        raise SystemExit("ATOLYE_LOCAL_ONLY: an attempted network operation invalidated this run")
    raise SystemExit(exit_code)


if __name__ == "__main__":
    main()
