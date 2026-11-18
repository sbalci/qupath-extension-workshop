"""Local preparation/guard contracts. No model packages or network are required."""
import contextlib
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parent.parent
BRIDGES = ROOT / "src/main/resources/python"


class LocalWorkflowTests(unittest.TestCase):
    def launch(self, body):
        with tempfile.TemporaryDirectory(prefix="atolye-local-test-") as folder:
            script = Path(folder) / "fixture.py"
            script.write_text(body, encoding="utf-8")
            return subprocess.run([sys.executable, "-I", str(BRIDGES / "workflow/local_only.py"), str(script)],
                                  capture_output=True, text=True, timeout=15)

    def test_prepared_local_script_runs(self):
        result = self.launch("print('LOCAL_OK')")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("LOCAL_OK", result.stdout)

    def test_network_is_blocked_before_dns(self):
        result = self.launch("import socket\nsocket.getaddrinfo('example.invalid',443)")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ATOLYE_LOCAL_ONLY", result.stderr)

    def test_caught_network_error_still_invalidates_the_run(self):
        result = self.launch("import socket\ntry:\n socket.getaddrinfo('example.invalid',443)\nexcept RuntimeError:\n pass\nprint('CACHED_FALLBACK')")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("invalidated", result.stderr)

    def test_nuclick_local_weights_never_call_a_fetcher(self):
        spec = importlib.util.spec_from_file_location("local_nuclick_test", BRIDGES / "nuclick/nuclick_bridge.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.NamedTemporaryFile() as weights:
            with patch.dict(os.environ, {"ATOLYE_LOCAL_WEIGHTS": weights.name}):
                self.assertEqual(module.weights_path(), weights.name)
        with patch.dict(os.environ, {"ATOLYE_LOCAL_WEIGHTS": "missing-local-weights.pt"}):
            with self.assertRaises(SystemExit):
                module.weights_path()

    def test_nuclick_explicit_cuda_has_no_silent_fallback(self):
        spec = importlib.util.spec_from_file_location("local_nuclick_device_test", BRIDGES / "nuclick/nuclick_bridge.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        fake_torch = types.SimpleNamespace(cuda=types.SimpleNamespace(is_available=lambda: False))
        with patch.dict(sys.modules, {"torch": fake_torch}), patch.dict(os.environ, {"ATOLYE_STRICT_DEVICE": "1"}):
            with self.assertRaises(SystemExit):
                module.resolve_device("cuda")
            self.assertEqual(module.resolve_device("cpu"), "cpu")


if __name__ == "__main__":
    unittest.main()
