"""Inspect fixed public career pages, without database access or bypassing blocks."""
from urllib.request import Request, urlopen
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser
import json

URLS = [
    'https://carreiras.itau.com.br/vaga/sao-paulo/engenharia-de-software-pleno-sre-pagar-o-que-devo-pj/35299/102040746144',
    'https://www.empregare.com/pt-br/vaga-analista-cloud-security_184313',
    'https://sicoob.empregare.com/pt-br/vaga-jovem-aprendiz-tecnologia_182523',
    'https://carreiras.itau.com.br/robots.txt',
    'https://carreiras.itau.com.br/busca-de-vagas',
    'https://sicoob.empregare.com/robots.txt',
    'https://sicoob.empregare.com/pt-br/vagas-de-TI-?pagina=1',
    'https://www.empregare.com/robots.txt',
    'https://www.empregare.com/pt-br/vagas-de-tecnologia',
    'https://carreiras.ifood.com.br/robots.txt',
    'https://carreiras.ifood.com.br/',
    'https://santander.wd3.myworkdayjobs.com/robots.txt',
    'https://www.infojobs.com.br/robots.txt',
]


class Links(HTMLParser):
    def __init__(self):
        super().__init__(); self.links=[]; self.ld=0
    def handle_starttag(self, tag, attrs):
        attrs=dict(attrs)
        if tag=='a' and any(s in attrs.get('href','') for s in ['/vaga','/job','greenhouse','workday']):
            self.links.append(attrs['href'])
        if tag=='script' and attrs.get('type')=='application/ld+json': self.ld+=1


def probe(url):
    try:
        with urlopen(Request(url,headers={'User-Agent':'Techedin/1.0 public-source-check'}),timeout=20) as response:
            text=response.read(3_000_000).decode('utf-8',errors='replace')
            if url.endswith('robots.txt'): return {'url':url,'status':response.status,'robots':text[:200]}
            parser=Links();parser.feed(text)
            import re
            ld=re.findall(r'<script[^>]*type=[\"\']application/ld\+json[\"\'][^>]*>(.*?)</script>',text,re.S|re.I)
            scripts=re.findall(r'<script[^>]*src=[\"\']([^\"\']+)',text,re.I)
            return {'url':url,'status':response.status,'ld':parser.ld,'sample':ld[0][:1100] if ld else '', 'scripts':scripts[-5:],'links':list(dict.fromkeys(parser.links))[:5]}
    except Exception as error: return {'url':url,'error':str(error)[:150]}


if __name__=='__main__':
    with ThreadPoolExecutor(max_workers=4) as executor:
        for result in executor.map(probe,URLS): print(json.dumps(result,ensure_ascii=True))
