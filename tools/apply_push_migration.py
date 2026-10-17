"""Inspect or apply fixed additive V6 on the manually managed database. No account/job deletion."""
import argparse
from pathlib import Path
from check_database import connect
ROOT=Path(__file__).resolve().parents[1]
COLUMNS={'subscription_id','user_id','endpoint_hash','endpoint','created_at','cursor_job_id','pending_through','attempts','retry_after','lease_until','last_sent_at'}
def migrate(conn,apply=False):
    conn.set_session(readonly=not apply)
    try:
        with conn.cursor() as cur:
            cur.execute("SET LOCAL lock_timeout='5s'");cur.execute("SET LOCAL statement_timeout='20s'")
            if apply:cur.execute("SELECT pg_advisory_xact_lock(hashtext('techedin-push-v6'))")
            cur.execute("SELECT to_regclass('public.flyway_schema_history')")
            if cur.fetchone()[0] and apply:raise ValueError('O banco usa Flyway; aplique a V6 pelo backend.')
            cur.execute("SELECT to_regclass('public.app_push_subscription')")
            exists=cur.fetchone()[0] is not None
            if not exists and not apply:
                print('V6 pendente; inspeção somente leitura.');conn.rollback();return False
            if not exists:
                cur.execute('SET LOCAL search_path=public')
                cur.execute((ROOT/'database/migrations/V6__private_push_subscriptions.sql').read_text(encoding='utf-8'))
            cur.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='app_push_subscription'")
            if {row[0] for row in cur.fetchall()}!=COLUMNS:raise ValueError('Tabela push difere da V6; nenhuma alteração foi mantida.')
            cur.execute("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='dim_job' AND column_name='discovered_at' AND data_type='timestamp with time zone'")
            if cur.fetchone()[0]!=1:raise ValueError('Coluna de descoberta ausente/incompatível.')
            cur.execute("SELECT relrowsecurity FROM pg_class WHERE oid='public.app_push_subscription'::regclass")
            if not cur.fetchone()[0]:raise ValueError('Tabela push sem RLS.')
            cur.execute("SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename='app_push_subscription'")
            if cur.fetchone()[0]:raise ValueError('Tabela push tem políticas públicas que exigem revisão.')
            cur.execute("SELECT rolname FROM pg_roles WHERE rolname IN ('anon','authenticated')")
            for (role,) in cur.fetchall():
                cur.execute("SELECT has_table_privilege(%s,'public.app_push_subscription','SELECT,INSERT,UPDATE,DELETE')",(role,))
                if cur.fetchone()[0]:raise ValueError('Permissão pública indevida na tabela push.')
        if apply:conn.commit()
        else:conn.rollback()
        print('V6 privada verificada; nenhuma vaga/conta foi excluída.');return True
    except Exception:
        conn.rollback();raise
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    conn=None
    try:
        conn=connect();migrate(conn,args.apply)
    except ValueError as error:print(error);raise SystemExit(1)
    except Exception as error:print('Falha:',type(error).__name__,'. Sem alteração parcial.');raise SystemExit(1)
    finally:
        if conn:conn.close()
