"""Check Gemini configuration without printing keys, provider bodies or any saved resume."""
import argparse
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', default=None)
    parser.add_argument('--generate-test', action='store_true', help='Send one synthetic career question; never reads user data.')
    args = parser.parse_args()
    settings = dotenv_values(ROOT / 'ingestion/.env')
    key = settings.get('GEMINI_API_KEY')
    model = args.model or settings.get('GEMINI_MODEL') or 'gemini-3.8-flash'
    import re
    if not re.fullmatch(r'[a-zA-Z0-9.-]+', model):
        print('Nome de modelo inválido.'); return 1
    if not key:
        print('GEMINI_API_KEY ausente no arquivo local.'); return 1
    url = 'https://generativelanguage.googleapis.com/v1beta/models'
    data = None
    if args.generate_test:
        url += '/' + model + ':generateContent'
        data = json.dumps({'contents': [{'parts': [{'text': 'Em uma frase, dê uma dica geral para melhorar um currículo.'}]}],
                           'generationConfig': {'maxOutputTokens': 2048}}).encode()
    try:
        with urlopen(Request(url, data=data, headers={'x-goog-api-key': key, 'Content-Type': 'application/json'}), timeout=50) as response:
            result = json.load(response)
        if args.generate_test:
            candidates = result.get('candidates', [])
            finish = candidates[0].get('finishReason') if candidates else None
            has_text = any(part.get('text') and not part.get('thought') for c in candidates for part in c.get('content', {}).get('parts', []))
            print('Modelo:', model, '| HTTP: 200 | Resposta textual:', bool(has_text), '| Finalização:', finish)
            return 0 if has_text and finish == 'STOP' else 1
        available = {item.get('name') for item in result.get('models', []) if 'generateContent' in item.get('supportedGenerationMethods', [])}
        print('Modelo consultado:', model, '| Disponível para generateContent:', 'models/' + model in available)
        print('gemini-2.5-flash disponível:', 'models/gemini-2.5-flash' in available)
        return 0 if 'models/' + model in available else 1
    except HTTPError as error:
        labels = {400: 'Solicitação/configuração rejeitada.', 401: 'Chave inválida.', 403: 'Chave sem permissão ou restrição do projeto.',
                  404: 'Modelo não encontrado.', 429: 'Cota ou limite do provedor atingido.'}
        print('Gemini HTTP', error.code, labels.get(error.code, 'Serviço do provedor indisponível.'))
        return 1
    except Exception as error:
        print('Não foi possível consultar o Gemini:', type(error).__name__)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
