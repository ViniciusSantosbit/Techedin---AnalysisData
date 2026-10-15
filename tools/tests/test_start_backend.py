import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from start_backend import jdbc_environment, validate_schema, EXPECTED_COLUMNS


class BackendStartupTests(unittest.TestCase):
    def test_credentials_stay_in_environment_and_ssl_reaches_jdbc(self):
        values = {'DB_HOST': 'example.supabase.co', 'DB_PORT': '5432', 'DB_NAME': 'postgres',
                  'DB_USER': 'postgres', 'DB_PASSWORD': 'fixture-password', 'DB_SSLMODE': 'require'}
        environment = jdbc_environment(values)
        self.assertIn('sslmode=require', environment['DB_URL'])
        self.assertNotIn(values['DB_PASSWORD'], environment['DB_URL'])
        self.assertNotIn('DB_URL', values)
        self.assertEqual(environment['DB_USER'], 'postgres')

    def test_rejects_a_connection_url_in_host_field(self):
        with self.assertRaises(ValueError):
            jdbc_environment({'DB_HOST': 'postgres://user:password@host/db', 'DB_USER': 'user', 'DB_PASSWORD': 'password'})

    def test_transaction_pooler_disables_named_prepared_statements(self):
        environment = jdbc_environment({'DB_HOST': 'example.pooler.supabase.com', 'DB_PORT': '6543',
            'DB_USER': 'fixture', 'DB_PASSWORD': 'fixture-password', 'DB_SSLMODE': 'require'})
        self.assertIn('prepareThreshold=0', environment['DB_URL'])

    def connection(self, rows):
        connection = Mock()
        cursor = Mock()
        cursor.__enter__ = Mock(return_value=cursor)
        cursor.__exit__ = Mock(return_value=False)
        connection.cursor.return_value = cursor
        cursor.fetchall.return_value = rows
        return connection

    def test_manual_schema_check_is_read_only_and_refuses_missing_tables(self):
        connection = self.connection([])
        with self.assertRaisesRegex(ValueError, 'schema manual'):
            validate_schema(connection, True)
        connection.set_session.assert_called_once_with(readonly=True)
        connection.rollback.assert_called_once()
        queries = [call.args[0].strip() for call in connection.cursor.return_value.execute.call_args_list]
        self.assertTrue(all(query.startswith('SELECT') for query in queries))

    def test_accepts_complete_manual_schema_without_updating_flyway_history(self):
        rows = [(table, column) for table, columns in EXPECTED_COLUMNS.items() for column in columns]
        connection = self.connection(rows)
        validate_schema(connection, True)
        self.assertEqual(connection.cursor.return_value.execute.call_count, 1)

    def test_refuses_automatic_migrations_over_untracked_v4(self):
        connection = self.connection([('app_user', 'user_id')])
        with self.assertRaisesRegex(ValueError, 'sem registro Flyway'):
            validate_schema(connection, False)
        connection.rollback.assert_called_once()
