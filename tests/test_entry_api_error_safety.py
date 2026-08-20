import sqlite3
import unittest
from unittest.mock import patch

from nxr_admin import admin_core
from nxr_admin import routes_entries


class EntryApiErrorSafetyTests(unittest.TestCase):
    def test_pop_database_failure_returns_generic_message(self):
        with (
            patch.object(
                routes_entries,
                'calculate_population_for_card_data',
                side_effect=sqlite3.OperationalError(
                    'database is locked at /private/database/path'
                ),
            ),
            admin_core.app.test_request_context(
                '/admin/api/calculate-pop',
                method='POST',
                json={
                    'product_type': 'graded_card',
                    'card_category': 'trading_card',
                    'card_name': 'Card',
                    'set_name': 'Set',
                    'card_number': '1',
                    'language': 'EN',
                    'final_grade_text': '9',
                },
            ),
        ):
            response, status = routes_entries.api_calculate_pop.__wrapped__()

        payload = response.get_json()
        self.assertEqual(status, 503)
        self.assertEqual(payload['pop'], '1')
        self.assertIn('temporarily unavailable', payload['error'])
        self.assertNotIn('database is locked', payload['error'])
        self.assertNotIn('/private/', payload['error'])

    def test_match_database_failure_returns_generic_message(self):
        with (
            patch.object(
                routes_entries,
                'get_temp_db_connection',
                side_effect=sqlite3.OperationalError(
                    'unable to open /private/database/path'
                ),
            ),
            admin_core.app.test_request_context(
                '/admin/api/match-card',
                method='POST',
                json={
                    'product_type': 'graded_card',
                    'card_category': 'trading_card',
                    'set_name': 'Set',
                    'card_number': '1',
                },
            ),
        ):
            response, status = routes_entries.api_match_card.__wrapped__()

        payload = response.get_json()
        self.assertEqual(status, 503)
        self.assertIn('temporarily unavailable', payload['error'])
        self.assertNotIn('unable to open', payload['error'])
        self.assertNotIn('/private/', payload['error'])


if __name__ == '__main__':
    unittest.main()
