"""Configure the same-origin API proxy after the backend has a real HTTPS address."""
import argparse
import ipaddress
import json
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def configure(api_url, destination):
    parsed = urlsplit(api_url)
    host = parsed.hostname
    if (parsed.scheme != 'https' or not host or parsed.username or parsed.password
            or parsed.query or parsed.fragment or parsed.path not in ('', '/')
            or parsed.port not in (None, 443) or host == 'localhost' or host.endswith('.localhost')):
        raise ValueError('Informe somente a origem HTTPS pública da API, sem caminho, credenciais ou parâmetros.')
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        if '.' not in host:
            raise ValueError('Informe um domínio público válido para a API.')
    else:
        if not address.is_global:
            raise ValueError('A Vercel precisa de uma API pública; endereços locais não funcionam.')
    config = json.loads(destination.read_text(encoding='utf-8'))
    rewrites = config.get('rewrites', [])
    rewrites = [rule for rule in rewrites if rule.get('source') != '/api/:path*']
    config['rewrites'] = [{'source': '/api/:path*', 'destination': api_url.rstrip('/') + '/api/:path*'}, *rewrites]
    destination.write_text(json.dumps(config, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api-url', required=True)
    args = parser.parse_args()
    try:
        configure(args.api_url, ROOT / 'frontend/vercel.json')
    except (ValueError, OSError):
        parser.exit(1, 'Não foi possível configurar. Use uma origem HTTPS pública e um vercel.json válido.\n')
    print('Proxy /api configurado em frontend/vercel.json. Revise o arquivo e publique o frontend novamente.')
