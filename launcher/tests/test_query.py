"""query.py on byte fixtures and against local fake UDP/TCP servers (127.0.0.1 only)."""
import json
import random
import socket
import struct
import threading
import time
import unittest

from helpers import TempEnv  # noqa: F401  (sys.path setup)

from gmodcraft_launcher import query as q

FF = b"\xff\xff\xff\xff"


def cstr(s):
    return s.encode() + b"\x00"


def info_payload(name="Test", map_="gm_construct", players=2, maxp=8, vis=1, vac=1, version="2026.04.29"):
    return (b"I\x11" + cstr(name) + cstr(map_) + cstr("garrysmod") + cstr("Sandbox") + struct.pack("<h", 4000)
            + bytes([players, maxp, 0, ord("d"), ord("l"), vis, vac]) + cstr(version) + b"\x80" + struct.pack("<h", 27015))


def rules_payload(rules):
    body = b"".join(cstr(k) + cstr(v) for k, v in rules.items())
    return b"E" + struct.pack("<h", len(rules)) + body


def split(payload, size, pid=0x1234, total=None, numbers=None):
    whole = FF + payload
    parts = [whole[i:i + size] for i in range(0, len(whole), size)]
    total = len(parts) if total is None else total
    numbers = range(len(parts)) if numbers is None else numbers
    return [b"\xfe\xff\xff\xff" + struct.pack("<lBBh", pid, total, n, 1248) + parts[n if n < len(parts) else 0] for n in numbers]


class ParseTest(unittest.TestCase):
    def test_info_good(self):
        r = q.parse_info(info_payload())
        self.assertEqual((r.name, r.map, r.players, r.max_players, r.password, r.vac, r.version),
                         ("Test", "gm_construct", 2, 8, True, True, "2026.04.29"))

    def test_info_malformed_and_truncated(self):
        good = info_payload()
        for bad in (b"", b"J" + good[1:], good[:10], good[:good.index(b"Sandbox") + 9], b"I\x11" + b"x" * 5000,
                    info_payload(vis=7)):
            with self.subTest(bad=bad[:20]), self.assertRaises(q.QueryError):
                q.parse_info(bad)

    def test_info_text_is_cleaned(self):
        r = q.parse_info(info_payload(name="evil\x1b[2J‮name" + "x" * 500, map_="m\napX"))
        self.assertNotIn("\x1b", r.name)
        self.assertLessEqual(len(r.name), q.MAX_TEXT)
        self.assertEqual(r.map, "mapX")

    def test_rules(self):
        self.assertEqual(q.parse_rules(rules_payload({"a": "1", "gmodcraft_version": "16/0.1.2"})),
                         {"a": "1", "gmodcraft_version": "16/0.1.2"})
        for bad in (b"E\x02\x00a\x001\x00", b"E" + struct.pack("<h", -1), b"E" + struct.pack("<h", q.MAX_RULES + 1),
                    b"X\x00\x00", b"E\x01\x00" + b"k" * 300 + b"\x00v\x00"):
            with self.subTest(bad=bad[:12]), self.assertRaises(q.QueryError):
                q.parse_rules(bad)

    def test_version_values(self):
        self.assertEqual(q.parse_version("16/0.1.2"), (16, "0.1.2"))
        for bad in (None, "", "16", "x/1", "/1", "1" * 7 + "/x", "16/" + "x" * 80):
            self.assertIsNone(q.parse_version(bad))

    def test_reassembly(self):
        payload = rules_payload({f"cvar{i}": "v" * 40 for i in range(200)})
        frags = split(payload, 1200)
        self.assertGreater(len(frags), 3)
        ra = q.Reassembler()
        out = None
        for f in reversed(frags):            # any order
            out = ra.add(f)
        self.assertEqual(out, payload)

    def test_reassembly_refusals(self):
        payload = rules_payload({f"c{i}": "v" * 50 for i in range(100)})
        frags = split(payload, 1000)
        cases = {
            "duplicate": [frags[0], frags[0]],
            "out of range": split(payload, 1000, total=2, numbers=[2]),
            "too many fragments": split(payload, 100, total=q.MAX_FRAGMENTS + 1, numbers=[0]),
            "compressed": split(payload, 1000, pid=-0x7fffffff),
            "other reply": [frags[0], split(payload, 1000, pid=99)[1]],
        }
        for what, pkts in cases.items():
            with self.subTest(what), self.assertRaises(q.QueryError):
                ra = q.Reassembler()
                for p in pkts:
                    ra.add(p)
        big = rules_payload({f"c{i}": "v" * 200 for i in range(330)})       # > 64 KiB
        with self.assertRaises(q.QueryError):
            ra = q.Reassembler()
            for p in split(big, 4000):
                ra.add(p)
        ra = q.Reassembler()                                                 # missing one: never complete
        self.assertIsNone(ra.add(frags[0]))
        self.assertIsNone(ra.add(frags[2]))

    def test_varint(self):
        for n in (0, 1, 127, 128, 255, 25565, 2 ** 31 - 1, -1):
            data = iter(q.varint(n))
            self.assertEqual(q.read_varint(lambda: next(data)), n)
        data = iter(b"\xff" * 6)
        with self.assertRaises(q.QueryError):
            q.read_varint(lambda: next(data))

    def test_mc_response(self):
        doc = json.dumps({"version": {"name": "26.3", "protocol": 777}, "players": {"online": 1, "max": 20},
                          "description": {"text": "hi"}}).encode()
        r = q.parse_mc_response(b"\x00" + q.varint(len(doc)) + doc)
        self.assertEqual((r.online, r.max_players, r.version, r.protocol), (1, 20, "26.3", 777))
        for bad in (b"\x01\x02{}", b"\x00\x05{}", b"\x00\x02[]", b"\x00\x03{x}",
                    b"\x00" + q.varint(q.MC_MAX_JSON + 1) + b"{}",
                    b"\x00" + q.varint(40) + b'{"players": {"online": -1, "max": 5}}  ',
                    b"\x00" + q.varint(37) + b'{"players":{"online":true,"max":5}} '):
            with self.subTest(bad=bad[:16]), self.assertRaises(q.QueryError):
                q.parse_mc_response(bad)

    def test_garbage_never_raises_other_errors(self):
        rnd = random.Random(7)
        for _ in range(2000):
            blob = bytes(rnd.randrange(256) for _ in range(rnd.randrange(60)))
            for fn in (q.parse_info, q.parse_rules, q.parse_mc_response):
                try:
                    fn(blob)
                except (q.QueryError, UnicodeError):
                    pass


class FakeUdp:
    """A local UDP server answering each datagram with handler(request) -> [packets]."""

    def __init__(self, handler):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.port = self.sock.getsockname()[1]
        self.handler = handler
        self.requests = []
        self.stop = False
        self.t = threading.Thread(target=self.loop, daemon=True)
        self.t.start()

    def loop(self):
        self.sock.settimeout(0.1)
        while not self.stop:
            try:
                data, addr = self.sock.recvfrom(65535)
            except socket.timeout:
                continue
            except OSError:
                return
            self.requests.append(data)
            for p in self.handler(data):
                self.sock.sendto(p, addr)

    def close(self):
        self.stop = True
        self.t.join(1)
        self.sock.close()


class UdpTest(unittest.TestCase):
    def serve(self, handler):
        srv = FakeUdp(handler)
        self.addCleanup(srv.close)
        return srv

    def test_info_with_challenge(self):
        def h(req):
            if req == q.A2S_INFO_REQ:
                return [FF + b"A" + b"\x01\x02\x03\x04"]
            if req == q.A2S_INFO_REQ + b"\x01\x02\x03\x04":
                return [FF + info_payload(players=3)]
            return []
        srv = self.serve(h)
        r = q.a2s_info("127.0.0.1", srv.port)
        self.assertTrue(r.ok, r.error)
        self.assertEqual(r.players, 3)
        self.assertEqual(len(srv.requests), 2)

    def test_challenge_only_retried_once(self):
        srv = self.serve(lambda req: [FF + b"A" + bytes(4)])
        r = q.a2s_info("127.0.0.1", srv.port)
        self.assertFalse(r.ok)
        self.assertIn("twice", r.error)
        self.assertEqual(len(srv.requests), 2)
        r = q.a2s_rules("127.0.0.1", srv.port)
        self.assertFalse(r.ok)

    def test_rules_split_with_challenge(self):
        rules = {f"cvar_{i}": "x" * 30 for i in range(150)}
        rules[q.VERSION_CONVAR] = f"{q.PROTOCOL}/0.1.2"

        def h(req):
            if req == q.A2S_RULES_REQ + FF:
                return [FF + b"A" + b"abcd"]
            if req == q.A2S_RULES_REQ + b"abcd":
                return list(reversed(split(rules_payload(rules), 1200)))
            return []
        srv = self.serve(h)
        r = q.a2s_rules("127.0.0.1", srv.port)
        self.assertTrue(r.ok, r.error)
        self.assertEqual(r.rules[q.VERSION_CONVAR], f"{q.PROTOCOL}/0.1.2")
        st = q.ServerStatus(q.InfoResult(ok=True), r, q.McResult())
        self.assertEqual(st.gmodcraft_version, (q.PROTOCOL, "0.1.2"))
        self.assertTrue(st.protocol_match)

    def test_split_missing_fragment_times_out(self):
        frags = split(rules_payload({f"c{i}": "v" * 40 for i in range(100)}), 1000)
        srv = self.serve(lambda req: frags[:1] + frags[2:])
        t = time.monotonic()
        r = q.a2s_rules("127.0.0.1", srv.port, timeout=0.5)
        self.assertFalse(r.ok)
        self.assertIn("timed out", r.error)
        self.assertLess(time.monotonic() - t, 1.5)

    def test_split_duplicate_and_oversize(self):
        frags = split(rules_payload({f"c{i}": "v" * 40 for i in range(100)}), 1000)
        srv = self.serve(lambda req: [frags[0], frags[0]])
        self.assertIn("duplicate", q.a2s_rules("127.0.0.1", srv.port).error)
        big = split(rules_payload({f"c{i}": "v" * 200 for i in range(330)}), 4000)
        srv2 = self.serve(lambda req: big)
        self.assertIn("too large", q.a2s_rules("127.0.0.1", srv2.port).error)

    def test_garbage_and_silence(self):
        srv = self.serve(lambda req: [b"\x00junk"])
        self.assertFalse(q.a2s_info("127.0.0.1", srv.port).ok)
        silent = self.serve(lambda req: [])
        t = time.monotonic()
        r = q.a2s_info("127.0.0.1", silent.port, timeout=30)      # capped at 2 s
        self.assertFalse(r.ok)
        self.assertLess(time.monotonic() - t, q.TIMEOUT + 0.5)
        self.assertFalse(q.a2s_info("no-such-host.invalid", 27015).ok)
        self.assertFalse(q.a2s_info("127.0.0.1", "x").ok)

    def test_rules_disabled_is_unknown(self):
        srv = self.serve(lambda req: [FF + info_payload()] if req.startswith(q.A2S_INFO_REQ) else [])
        st = q.server_status("127.0.0.1", srv.port, mc_port=None, timeout=0.5)
        self.assertTrue(st.online)
        self.assertFalse(st.rules.ok)
        self.assertIsNone(st.gmodcraft_version)
        self.assertIsNone(st.protocol_match)


class FakeTcp:
    def __init__(self, reply):
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(4)
        self.port = self.sock.getsockname()[1]
        self.reply = reply
        self.got = b""
        self.t = threading.Thread(target=self.loop, daemon=True)
        self.t.start()

    def loop(self):
        try:
            c, _ = self.sock.accept()
        except OSError:
            return
        with c:
            c.settimeout(1)
            try:
                self.got = c.recv(4096)
                if self.reply is not None:
                    c.sendall(self.reply)
                time.sleep(0.3)
            except OSError:
                pass

    def close(self):
        self.sock.close()


class McTest(unittest.TestCase):
    def serve(self, reply):
        srv = FakeTcp(reply)
        self.addCleanup(srv.close)
        return srv

    @staticmethod
    def packet(doc):
        body = b"\x00" + q.varint(len(doc)) + doc
        return q.varint(len(body)) + body

    def test_good(self):
        doc = json.dumps({"version": {"name": "26.3", "protocol": 777}, "players": {"online": 2, "max": 20}}).encode()
        srv = self.serve(self.packet(doc))
        r = q.mc_status("127.0.0.1", srv.port)
        self.assertTrue(r.ok, r.error)
        self.assertEqual((r.online, r.max_players, r.version), (2, 20, "26.3"))
        self.assertIn(b"127.0.0.1", srv.got)
        self.assertTrue(srv.got.endswith(b"\x01\x00"))           # the status request follows the handshake

    def test_bad_replies(self):
        cases = {
            "oversize": q.varint(q.MC_MAX_JSON + 100) + b"\x00",
            "truncated": self.packet(b'{"players": {"online": 1, "max": 2}}')[:-5],
            "malformed json": self.packet(b"{nope"),
            "bad varint": b"\xff\xff\xff\xff\xff\xff",
            "silence": None,
        }
        for what, reply in cases.items():
            with self.subTest(what):
                srv = self.serve(reply)
                r = q.mc_status("127.0.0.1", srv.port, timeout=0.5)
                self.assertFalse(r.ok)
                self.assertTrue(r.error)

    def test_refused(self):
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        r = q.mc_status("127.0.0.1", port)
        self.assertFalse(r.ok)


if __name__ == "__main__":
    unittest.main()
