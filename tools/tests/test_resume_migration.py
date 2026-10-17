import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import apply_resume_migration as migration


class ResumeMigrationTests(unittest.TestCase):
    def setup_connection(self, values):
        connection = Mock()
        cursor = Mock()
        cursor.__enter__ = Mock(return_value=cursor)
        cursor.__exit__ = Mock(return_value=False)
        cursor.fetchone.side_effect = values
        connection.cursor.return_value = cursor
        return connection, cursor

    def test_default_inspection_does_not_write_or_commit(self):
        connection, cursor = self.setup_connection([(None,), (None,)])
        self.assertFalse(migration.migrate(connection))
        connection.set_session.assert_called_once_with(readonly=True)
        self.assertTrue(all(call.args[0].startswith('SELECT') for call in cursor.execute.call_args_list))
        connection.commit.assert_not_called()

    def test_refuses_applying_over_flyway_managed_schema(self):
        connection, cursor = self.setup_connection([('flyway_schema_history',)])
        with self.assertRaisesRegex(ValueError, 'Flyway'):
            migration.migrate(connection, True)
        connection.rollback.assert_called_once()
        connection.commit.assert_not_called()

    def test_security_failure_rolls_back_new_table(self):
        connection, cursor = self.setup_connection([(None,), (None,)])
        with patch.object(migration, 'validate_private_table', side_effect=ValueError('unsafe')):
            with self.assertRaises(ValueError):
                migration.migrate(connection, True)
        connection.rollback.assert_called_once()
        connection.commit.assert_not_called()
