"""Inspect or apply only V5 to a database whose schema is managed manually. No user rows are changed."""
import argparse
from pathlib import Path
from check_database import connect
from start_backend import EXPECTED_COLUMNS

ROOT = Path(__file__).resolve().parents[1]


def validate_private_table(cursor):
    cursor.execute("""SELECT column_name FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'app_user_resume'""")
    columns = {row[0] for row in cursor.fetchall()}
    if columns != EXPECTED_COLUMNS['app_user_resume']:
        raise ValueError('A tabela de currículo existente difere da V5. Nenhuma alteração será feita.')
    cursor.execute("SELECT relrowsecurity FROM pg_class WHERE oid = 'public.app_user_resume'::regclass")
    if not cursor.fetchone()[0]:
        raise ValueError('A tabela de currículo está sem RLS; revise a V5 antes de continuar.')
    cursor.execute("SELECT COUNT(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = 'app_user_resume'")
    if cursor.fetchone()[0]:
        raise ValueError('Há políticas na tabela privada de currículo que precisam de revisão.')
    cursor.execute("SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated')")
    for (role,) in cursor.fetchall():
        cursor.execute("SELECT has_table_privilege(%s, 'public.app_user_resume', 'SELECT,INSERT,UPDATE,DELETE')", (role,))
        if cursor.fetchone()[0]:
            raise ValueError('A tabela de currículo ainda permite acesso pela Data API.')


def migrate(connection, apply=False):
    connection.set_session(readonly=not apply)
    try:
        with connection.cursor() as cursor:
            if apply:
                cursor.execute("SET LOCAL lock_timeout = '5s'")
                cursor.execute("SET LOCAL statement_timeout = '15s'")
                cursor.execute("SELECT pg_advisory_xact_lock(hashtext('techedin-resume-v5'))")
            cursor.execute("SELECT to_regclass('public.flyway_schema_history')")
            if cursor.fetchone()[0] and apply:
                raise ValueError('Este banco usa Flyway. Aplique a V5 pelo fluxo normal do backend.')
            cursor.execute("SELECT to_regclass('public.app_user_resume')")
            exists = cursor.fetchone()[0] is not None
            if not exists and not apply:
                print('V5 pendente. Inspeção somente leitura; nenhuma tabela foi criada.')
                connection.rollback()
                return False
            if not exists:
                cursor.execute("SET LOCAL search_path = public")
                sql = (ROOT / 'database/migrations/V5__private_resume_text.sql').read_text(encoding='utf-8')
                cursor.execute(sql)
            validate_private_table(cursor)
        if apply:
            connection.commit()
        else:
            connection.rollback()
        print('V5 aplicada e proteção da tabela verificada.' if not exists else 'Tabela de currículo já existente e privada; sem reaplicação de SQL.')
        return True
    except Exception:
        connection.rollback()
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Cria somente a tabela privada definida na V5, em uma transação.')
    args = parser.parse_args()
    try:
        connection = connect()
        try:
            migrate(connection, args.apply)
        finally:
            connection.close()
    except ValueError as error:
        print(error)
        raise SystemExit(1)
    except Exception as error:
        print('A operação não foi concluída: ' + type(error).__name__ + '. Nenhuma alteração parcial foi mantida.')
        raise SystemExit(1)
