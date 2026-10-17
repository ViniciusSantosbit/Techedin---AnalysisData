"""Start the API with local DB environment settings; never put credentials on the command line."""
import argparse
import os
import re
import subprocess
from pathlib import Path
from urllib.parse import quote, urlencode
from dotenv import load_dotenv
from check_database import connect

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_COLUMNS = {
    'dim_job': {'job_id', 'title', 'company', 'description', 'source', 'source_url', 'published_date', 'first_seen', 'is_demo', 'is_verified'},
    'dim_technology': {'technology_id', 'name', 'category'},
    'dim_date': {'date_id', 'date', 'day', 'month', 'year'},
    'dim_seniority': {'seniority_id', 'level'},
    'fact_job_technology': {'job_id', 'technology_id', 'date_id', 'seniority_id', 'mention_count'},
    'app_user': {'user_id', 'email', 'display_name', 'password_hash', 'created_at'},
    'app_user_preferences': {'user_id', 'target_role', 'location', 'work_model', 'seniority', 'updated_at'},
    'app_user_resume': {'user_id', 'revision', 'file_name', 'file_type', 'file_size', 'extracted_text', 'uploaded_at', 'updated_at'},
}


def jdbc_environment(settings):
    environment = dict(settings)
    missing = [key for key in ('DB_HOST', 'DB_USER', 'DB_PASSWORD') if not environment.get(key)]
    if missing:
        raise ValueError('Preencha ingestion/.env: ' + ', '.join(missing))
    host = environment['DB_HOST']
    if not re.fullmatch(r'[a-zA-Z0-9.:-]+', host):
        raise ValueError('DB_HOST deve conter apenas o endereço do banco, sem URL, usuário ou senha.')
    port = environment.get('DB_PORT', '5432')
    if not port.isdigit() or not 1 <= int(port) <= 65535:
        raise ValueError('DB_PORT deve ser uma porta válida.')
    ssl = environment.get('DB_SSLMODE', 'require')
    if ssl not in ('disable', 'allow', 'prefer', 'require', 'verify-ca', 'verify-full'):
        raise ValueError('DB_SSLMODE inválido.')
    jdbc_host = '[' + host + ']' if ':' in host else host
    database = quote(environment.get('DB_NAME', 'postgres'), safe='')
    options = {'sslmode': ssl, 'currentSchema': 'public', 'connectTimeout': '10'}
    if port == '6543':
        # Supabase transaction pooling cannot keep named prepared statements between transactions.
        options['prepareThreshold'] = '0'
    environment['DB_URL'] = 'jdbc:postgresql://' + jdbc_host + ':' + port + '/' + database + '?' + urlencode(options)
    return environment


def validate_schema(connection, manual):
    connection.set_session(readonly=True)
    try:
        with connection.cursor() as cursor:
            cursor.execute("""SELECT table_name, column_name FROM information_schema.columns
                WHERE table_schema = 'public'""")
            columns = {}
            for table, column in cursor.fetchall():
                columns.setdefault(table, set()).add(column)
            if manual:
                missing = [table + '.' + column for table, expected in EXPECTED_COLUMNS.items()
                    for column in sorted(expected - columns.get(table, set()))]
                if missing:
                    raise ValueError('O schema manual está incompleto. Faltam: ' + ', '.join(missing))
            elif 'app_user' in columns:
                versions = set()
                if 'flyway_schema_history' in columns:
                    cursor.execute('SELECT version FROM public.flyway_schema_history WHERE success = true')
                    versions = {row[0] for row in cursor.fetchall()}
                if '4' not in versions:
                    raise ValueError('A V4 existe sem registro Flyway. Use --manual-schema se o SQL foi aplicado manualmente.')
                if 'app_user_resume' in columns and '5' not in versions:
                    raise ValueError('A V5 existe sem registro Flyway. Use --manual-schema se o SQL foi aplicado manualmente.')
    finally:
        connection.rollback()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manual-schema', action='store_true',
        help='Valida tabelas/colunas e inicia sem migrations para banco administrado manualmente.')
    arguments = parser.parse_args()
    load_dotenv(ROOT / 'ingestion/.env', override=False)
    try:
        environment = jdbc_environment(os.environ)
        connection = connect()
        try:
            validate_schema(connection, arguments.manual_schema)
        finally:
            connection.close()
    except ValueError as error:
        print(error)
        return 1
    except Exception as error:
        print('Não foi possível conectar ao banco configurado (' + type(error).__name__ + ').')
        print('Confira ingestion/.env e execute: python tools/check_database.py')
        return 1
    if arguments.manual_schema:
        environment['SPRING_FLYWAY_ENABLED'] = 'false'
        print('Tabelas/colunas conferidas. Flyway desativado somente nesta execução; SQL manual não será reaplicado.', flush=True)
    print('Iniciando a API em http://localhost:8080. Mantenha este terminal aberto.', flush=True)
    wrapper = ROOT / 'backend' / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
    command = ['cmd.exe', '/d', '/c', str(wrapper), '-Pruntime', 'spring-boot:run'] if os.name == 'nt' else [str(wrapper), '-Pruntime', 'spring-boot:run']
    return subprocess.call(command, cwd=ROOT / 'backend', env=environment)


if __name__ == '__main__':
    raise SystemExit(main())
