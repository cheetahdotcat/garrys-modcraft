import unittest

from helpers import TempEnv

from gmodcraft_launcher import config, passwords, servers


class ServersTest(TempEnv):
    def test_make_validates(self):
        self.assertEqual(servers.make(" Remote ", " 192.168.133.109:27015 ", "25565"),
                         {"name": "Remote", "address": "192.168.133.109:27015", "mc_port": 25565})
        self.assertEqual(servers.make("", "play.example.org", None)["name"], "play.example.org")
        self.assertEqual(servers.make("a\x1bb" + "x" * 100, "h")["name"], "ab" + "x" * 62)
        for addr, port in (("-novid", 1), ("a b", 1), ("h", 0), ("h", 70000), ("h", "x")):
            with self.subTest(addr=addr, port=port), self.assertRaises(servers.ServerError):
                servers.make("n", addr, port)

    def test_load_drops_bad_and_duplicate_entries(self):
        cfg = {"servers": [{"name": "A", "address": "h:27015", "mc_port": 25565},
                           {"name": "dup", "address": "H", "mc_port": 1},      # same key as h:27015
                           {"name": "bad", "address": "-x"}, "junk", None,
                           {"name": "B", "address": "other:27016"}]}
        self.assertEqual([e["name"] for e in servers.load(cfg)], ["A", "B"])
        self.assertEqual(servers.load({"servers": "nope"}), [])
        many = {"servers": [{"name": str(i), "address": f"h{i}"} for i in range(80)]}
        self.assertEqual(len(servers.load(many)), servers.MAX_SERVERS)

    def test_migrate_old_single_server_and_its_password(self):
        passwords.remember("192.168.133.109:27015", "pw")
        cfg = config.load()
        cfg["server"] = "192.168.133.109:27015"
        self.assertTrue(servers.migrate(cfg))
        self.assertEqual(cfg["server"], "")
        self.assertEqual(cfg["servers"], [{"name": "192.168.133.109:27015", "address": "192.168.133.109:27015", "mc_port": 25565}])
        config.save(cfg)
        again = config.load()
        self.assertEqual(again["servers"], cfg["servers"])           # survives save/load (DEFAULTS key)
        self.assertEqual(passwords.stored(again["servers"][0]["address"]), "pw")
        self.assertNotIn("pw", config.path().read_text())
        self.assertFalse(servers.migrate(again))
        self.assertIsNot(config.load()["servers"], config.DEFAULTS["servers"])

    def test_migrate_invalid_old_address_drops_its_secret(self):
        p = passwords.secrets_path()
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text('{"format": 1, "servers": {"bad host!:27015": "orphan", "ok:27015": "keep"}}')
        cfg = config.load()
        cfg["server"] = "bad host!"
        self.assertTrue(servers.migrate(cfg))
        self.assertEqual(cfg["server"], "")
        self.assertNotIn("orphan", p.read_text())
        self.assertEqual(passwords.stored("ok"), "keep")

    def test_put_and_move(self):
        lst = []
        servers.put(lst, servers.make("A", "a"))
        servers.put(lst, servers.make("B", "b"))
        with self.assertRaises(servers.ServerError):
            servers.put(lst, servers.make("A2", "A:27015"))
        servers.put(lst, servers.make("A2", "a"), index=0)          # editing itself is fine
        self.assertEqual(servers.move(lst, 1, -1), 0)
        self.assertEqual([e["name"] for e in lst], ["B", "A2"])
        self.assertEqual(servers.move(lst, 0, -1), 0)


if __name__ == "__main__":
    unittest.main()
