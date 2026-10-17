"""Generate stable local VAPID keys once; never print the private key or overwrite an existing pair."""
import argparse,json,subprocess,re
from pathlib import Path
from urllib.parse import urlsplit
from dotenv import dotenv_values
ROOT=Path(__file__).resolve().parents[1]
def configure(subject,enable=False):
    if '\n' in subject or '\r' in subject or urlsplit(subject).scheme not in ('https','mailto'):raise ValueError('Use uma identificação HTTPS ou mailto válida.')
    path=ROOT/'ingestion/.env';values=dotenv_values(path)
    public,private=values.get('WEB_PUSH_PUBLIC_KEY'),values.get('WEB_PUSH_PRIVATE_KEY')
    if bool(public)!=bool(private):raise ValueError('Há uma chave incompleta. Preserve a existente e revise a configuração.')
    additions={}
    if not public:
        source="const c=require('node:crypto');const {privateKey}=c.generateKeyPairSync('ec',{namedCurve:'prime256v1'});const j=privateKey.export({format:'jwk'});process.stdout.write(JSON.stringify({publicKey:Buffer.concat([Buffer.from([4]),Buffer.from(j.x,'base64url'),Buffer.from(j.y,'base64url')]).toString('base64url'),privateKey:j.d}));"
        pair=json.loads(subprocess.run(['node','-e',source],capture_output=True,text=True,check=True).stdout)
        additions={'WEB_PUSH_PUBLIC_KEY':pair['publicKey'],'WEB_PUSH_PRIVATE_KEY':pair['privateKey']}
    if not values.get('WEB_PUSH_SUBJECT'):additions['WEB_PUSH_SUBJECT']=subject
    text=path.read_text(encoding='utf-8-sig')
    if enable:
        if re.search(r'^WEB_PUSH_ENABLED=.*$',text,re.M):text=re.sub(r'^WEB_PUSH_ENABLED=.*$','WEB_PUSH_ENABLED=true',text,flags=re.M)
        else:additions['WEB_PUSH_ENABLED']='true'
    elif 'WEB_PUSH_ENABLED' not in values:additions['WEB_PUSH_ENABLED']='false'
    path.write_text(text.rstrip()+'\n'+''.join(f'{key}={value}\n' for key,value in additions.items()),encoding='utf-8')
    print('Configuração push salva no arquivo ignorado. Par de chaves existente foi preservado.' if public else 'Par VAPID gerado e salvo no arquivo ignorado; chave privada não exibida.')
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--subject',default='https://techedin.vercel.app');parser.add_argument('--enable',action='store_true');args=parser.parse_args()
    configure(args.subject,args.enable)
