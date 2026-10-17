"""Apply only additive V7 to a manually managed DB; no existing accounts are verified or deleted."""
import argparse
from pathlib import Path
from check_database import connect

ROOT = Path(__file__).resolve().parents[1]


def migrate(connection, apply=False):
    connection.set_session(readonly=not apply)
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET LOCAL lock_timeout='5s'")
            cursor.execute("SET LOCAL statement_timeout='20s'")
            if apply: cursor.execute("SELECT pg_advisory_xact_lock(hashtext('techedin-account-v7'))")
            cursor.execute("SELECT to_regclass('public.flyway_schema_history'),to_regclass('public.app_account_token')")
            history, tokens = cursor.fetchone()
            if history and apply: raise ValueError('Use Flyway para este banco; nenhuma alteração aplicada.')
            if not tokens:
                if not apply:
                    print('V7 pendente. Inspeção somente leitura.'); connection.rollback(); return
                cursor.execute('SET LOCAL search_path=public')
                cursor.execute((ROOT/'database/migrations/V7__account_verification_and_recovery.sql').read_text(encoding='utf-8'))
            cursor.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='app_account_token'")
            if {row[0] for row in cursor.fetchall()} != {'token_hash','user_id','purpose','created_at','expires_at'}:
                raise ValueError('Tabela de tokens difere da V7; transação cancelada.')
            cursor.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='app_user' AND column_name IN ('email_verified_at','auth_version')")
            if len(cursor.fetchall()) != 2: raise ValueError('Colunas da V7 incompletas.')
            cursor.execute("SELECT relrowsecurity FROM pg_class WHERE oid='public.app_account_token'::regclass")
            if not cursor.fetchone()[0]: raise ValueError('Tabela de tokens sem RLS.')
            cursor.execute("SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename='app_account_token'")
            if cursor.fetchone()[0]: raise ValueError('Políticas públicas inesperadas na tabela privada.')
            cursor.execute("SELECT rolname FROM pg_roles WHERE rolname IN ('anon','authenticated')")
            for (role,) in cursor.fetchall():
                cursor.execute("SELECT has_table_privilege(%s,'public.app_account_token','SELECT,INSERT,UPDATE,DELETE')",(role,))
                if cursor.fetchone()[0]: raise ValueError('Permissão pública indevida em tokens.')
        if apply: connection.commit()
        else: connection.rollback()
        print('V7 privada verificada. Nenhuma conta ou vaga foi removida; e-mails existentes continuam sem confirmação.')
    except Exception:
        connection.rollback(); raise


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    connection=None
    try:
        connection=connect(); migrate(connection,args.apply)
    except ValueError as error: print(error); raise SystemExit(1)
    except Exception as error: print('Falha na V7:',type(error).__name__,'. Transação cancelada.'); raise SystemExit(1)
    finally:
        if connection: connection.close()
