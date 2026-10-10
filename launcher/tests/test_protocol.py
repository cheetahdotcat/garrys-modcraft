"""The launcher's link protocol is derived from protocol/gmodcraft_protocol.h, never pinned."""
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from helpers import TempEnv  # noqa: F401  (sys.path setup)

from gmodcraft_launcher import protocol, query as q

HERE = Path(__file__).resolve().parents[1]


class ProtocolTest(unittest.TestCase):
    def test_checkout_reads_the_header(self):
        kv = protocol.parse_kversion(protocol.HEADER.read_text())
        self.assertIsInstance(kv, int)
        self.assertEqual(protocol.link_protocol(), kv)
        self.assertEqual(q.PROTOCOL, kv)
        self.assertEqual(protocol.parse_kversion("inline constexpr std::uint32_t kVersion = 17;  // bumped"), 17)
        self.assertIsNone(protocol.parse_kversion("no version here"))

    def test_zipapp_carries_it(self):
        sys.path.insert(0, str(HERE))
        try:
            import build_bundle
        finally:
            sys.path.remove(str(HERE))
        with tempfile.TemporaryDirectory() as d:
            pyz = build_bundle.build_zipapp(Path(d) / "l.pyz")
            with zipfile.ZipFile(pyz) as z:
                self.assertIn("gmodcraft_launcher/protocol.json", z.namelist())
            code = ("import sys; sys.path.insert(0, sys.argv[1]); "
                    "from gmodcraft_launcher import protocol; print(protocol.link_protocol())")
            # run from a dir without a header next to it: the value can only come from protocol.json
            r = subprocess.run([sys.executable, "-c", code, str(pyz)], capture_output=True, text=True, timeout=60, cwd=d)
            self.assertEqual(r.stdout.strip(), str(protocol.link_protocol()), r.stderr)

    def test_unknown_protocols_never_mismatch(self):
        def st(v):
            return q.ServerStatus(q.InfoResult(ok=True), q.RulesResult(ok=True, rules={q.VERSION_CONVAR: v}), q.McResult())
        self.assertIsNone(st("0/0.1.2").protocol_match)                 # server's module not loaded
        self.assertFalse(st(f"{q.PROTOCOL + 1}/0.1.2").protocol_match)
        with mock.patch.object(q, "PROTOCOL", None):                    # launcher doesn't know its own
            self.assertIsNone(st("16/0.1.2").protocol_match)

    def test_hung_resolver_is_bounded(self):
        def slow(*a, **kw):
            time.sleep(5)
            raise OSError("late")
        with mock.patch("socket.getaddrinfo", side_effect=slow):
            t = time.monotonic()
            st = q.server_status("hangs.example", 27015, 25565, timeout=0.5)
            self.assertLess(time.monotonic() - t, 1.5)
        self.assertFalse(st.info.ok or st.rules.ok or st.mc.ok)
        self.assertIn("lookup timed out", st.info.error)
        self.assertEqual(q.resolve("10.1.2.3"), "10.1.2.3")
        self.assertFalse(q.server_status("no-such-host.invalid", 27015).online)


if __name__ == "__main__":
    unittest.main()
