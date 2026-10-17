"""Inspect configured PostgreSQL structure in a read-only transaction. Never print credentials."""
import os
from pathlib import Path
import psycopg2
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]


def connect():
    load_dotenv(ROOT / 'ingestion/.env', override=False)
    return psycopg2.connect(host=os.environ['DB_HOST'], port=os.getenv('DB_PORT', '5432'),
        dbname=os.getenv('DB_NAME', 'postgres'), user=os.environ['DB_USER'], password=os.environ['DB_PASSWORD'],
        sslmode=os.getenv('DB_SSLMODE', 'require'), connect_timeout=10)


def inspect():
    conn = connect()
    try:
        conn.set_session(readonly=True)
        with conn.cursor() as cursor:
            cursor.execute("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
            tables = {row[0] for row in cursor.fetchall()}
            required = {'dim_job', 'dim_technology', 'dim_date', 'dim_seniority', 'fact_job_technology',
                        'app_user', 'app_user_preferences', 'app_user_resume'}
            print('Tabelas necessárias presentes:', ', '.join(sorted(required & tables)))
            print('Tabelas necessárias ausentes:', ', '.join(sorted(required - tables)) or 'nenhuma')
            if 'flyway_schema_history' in tables:
                cursor.execute('SELECT version, success FROM public.flyway_schema_history ORDER BY installed_rank')
                print('Histórico Flyway:', cursor.fetchall())
            else:
                print('Histórico Flyway: não existe; schema administrado manualmente.')
            for table in ('dim_job', 'app_user', 'app_user_preferences', 'app_user_resume'):
                cursor.execute("""SELECT column_name, data_type, is_nullable FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = %s ORDER BY ordinal_position""", (table,))
                print(f'Colunas {table}:', cursor.fetchall())
            cursor.execute("""SELECT tablename, rowsecurity FROM pg_tables
                WHERE schemaname = 'public' AND tablename IN ('app_user', 'app_user_preferences', 'app_user_resume')""")
            print('RLS das tabelas de contas:', cursor.fetchall())
        conn.rollback()
    finally:
        conn.close()


if __name__ == '__main__':
    try:
        inspect()
    except Exception as error:
        # Connection exception text may contain a DSN; deliberately omit it.
        print(f'Falha ao inspecionar o banco: {type(error).__name__}; SQLSTATE={getattr(error, "pgcode", None)}')
        detail = str(error).lower()
        categories = (
            ('could not translate host name', 'O endereço DB_HOST não foi resolvido por DNS.'),
            ('password authentication failed', 'O banco rejeitou o usuário ou a senha configurados.'),
            ('tenant or user not found', 'O pooler rejeitou a identificação do projeto/usuário.'),
            ('connection refused', 'Não há um banco aceitando conexão no endereço/porta configurados.'),
            ('timeout', 'A conexão com o banco expirou.'),
            ('timed out', 'A conexão com o banco expirou.'),
            ('certificate', 'Falha na validação do certificado SSL.'),
            ('network is unreachable', 'A rede do endereço do banco não está acessível.'),
        )
        for marker, description in categories:
            if marker in detail:
                print(description)
                break
        else:
            print('Confira endereço, porta, projeto, usuário, senha e SSL no ambiente local.')
        raise SystemExit(1)
