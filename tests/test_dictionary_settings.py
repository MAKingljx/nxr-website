import tempfile
import unittest
from pathlib import Path

from nxr_admin import admin_core


class DictionarySettingsTests(unittest.TestCase):
    def setUp(self):
        self._db_path = admin_core.DB_PATH
        self._temp_db_path = admin_core.TEMP_DB_PATH
        self._data_dir = admin_core.DATA_DIR
        self._tmp_dir = tempfile.TemporaryDirectory()
        data_dir = Path(self._tmp_dir.name)
        admin_core.DATA_DIR = data_dir
        admin_core.DB_PATH = data_dir / "cards.db"
        admin_core.TEMP_DB_PATH = data_dir / "temp_cards.db"

    def tearDown(self):
        admin_core.DB_PATH = self._db_path
        admin_core.TEMP_DB_PATH = self._temp_db_path
        admin_core.DATA_DIR = self._data_dir
        self._tmp_dir.cleanup()

    def test_default_sports_type_options_are_seeded(self):
        with admin_core.get_main_db_connection() as conn:
            admin_core.initialize_dictionary_tables(conn)

        self.assertEqual(
            admin_core.get_sports_type_options(),
            ["Basketball", "Soccer", "Baseball", "Hockey", "Football", "Tennis"],
        )
        self.assertEqual(admin_core.normalize_sports_type("足球"), "Soccer")
        self.assertEqual(admin_core.normalize_sports_type(" ice hockey "), "Hockey")
        self.assertEqual(admin_core.normalize_sports_type("橄榄球"), "Football")

    def test_default_brand_options_are_seeded_as_dictionary(self):
        with admin_core.get_main_db_connection() as conn:
            admin_core.initialize_dictionary_tables(conn)
            conn.commit()

        self.assertIn("Pokemon", admin_core.get_brand_options())
        self.assertIn("Other", admin_core.get_brand_options())
        self.assertEqual(admin_core.normalize_brand("宝可梦"), "Pokemon")
        self.assertEqual(admin_core.normalize_brand("pokemon jpn"), "Pokemon")

        brand_group = admin_core.get_dictionary_group_by_code("brand")
        self.assertIsNotNone(brand_group)
        brand_items = admin_core.list_dictionary_items(group_code="brand")
        pokemon = next(item for item in brand_items if item["value"] == "Pokemon")
        self.assertIn("宝可梦", pokemon["aliases"])

    def test_brand_aliases_are_controlled_by_dictionary_items(self):
        with admin_core.get_main_db_connection() as conn:
            admin_core.initialize_dictionary_tables(conn)
            dc = conn.execute(
                """
                SELECT i.id, i.sort_order
                FROM dictionary_items i
                JOIN dictionary_groups g ON g.id = i.group_id
                WHERE g.code = ? AND i.value = ?
                """,
                ("brand", "DC"),
            ).fetchone()
            admin_core.update_dictionary_item(
                conn,
                dc["id"],
                "DC",
                aliases="",
                sort_order=dc["sort_order"],
                is_active=1,
            )
            conn.commit()

        self.assertIsNone(admin_core.get_brand_alias_map().get("dc comics"))
        self.assertEqual(admin_core.normalize_brand("dc comics"), "Other")
        self.assertEqual(admin_core.normalize_brand("DC"), "DC")

    def test_dictionary_options_use_database_values_exactly(self):
        with admin_core.get_main_db_connection() as conn:
            admin_core.initialize_dictionary_tables(conn)
            admin_core.create_dictionary_group(
                conn,
                "league_type",
                "League Type",
                sort_order=1,
            )
            group = conn.execute(
                "SELECT id FROM dictionary_groups WHERE code = ?",
                ("league_type",),
            ).fetchone()
            admin_core.create_dictionary_item(conn, group["id"], "NBA", sort_order=10)
            admin_core.create_dictionary_item(conn, group["id"], "NFL", sort_order=20)
            admin_core.create_dictionary_item(conn, group["id"], "MLB", sort_order=30)
            conn.commit()

        self.assertEqual(
            admin_core.get_dictionary_options("league_type"),
            ["NBA", "NFL", "MLB"],
        )

    def test_current_value_is_preserved_when_not_in_active_dictionary(self):
        with admin_core.get_main_db_connection() as conn:
            admin_core.initialize_dictionary_tables(conn)
            conn.commit()

        self.assertEqual(
            admin_core.get_sports_type_options(current_value="UFC"),
            ["Basketball", "Soccer", "Baseball", "Hockey", "Football", "Tennis", "UFC"],
        )


if __name__ == "__main__":
    unittest.main()
