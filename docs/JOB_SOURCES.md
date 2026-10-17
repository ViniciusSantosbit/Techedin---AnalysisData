# Fontes e operação da coleta

## Integrações disponíveis

| Fonte | Canal | Intervalo mínimo local | Situação |
|---|---|---|---|
| Gupy | Busca pública e páginas de anúncio | 15 min | Coletor existente; depende do HTML e de bloqueios do portal |
| Catho | Busca pública e páginas de anúncio | 15 min | Coletor existente; respostas 403 são registradas, sem tentar contornar bloqueios |
| We Work Remotely | RSS público de programação | 30 min | Integrado; atribuição e link originais |
| Remotive | API pública, categoria software-dev | 6 horas | Integrado; anúncios da API têm atraso de 24 horas |
| Nubank | API pública de vagas listadas da Ashby | 30 min | Integrado; canal atual confirmado no site oficial do Nubank |

Remotive e Nubank retornam somente anúncios que tenham título, descrição, link público do domínio esperado e pelo menos uma tecnologia do catálogo. A amostra é limitada a 100 anúncios válidos por fonte; o parâmetro `--max-jobs` limita a entrega de cada ciclo. `publishedAt` é a data de publicação da Ashby, sem substituir por `updatedAt`. Anúncios `isListed=false` são ignorados. A disponibilidade e as restrições geográficas devem ser conferidas no anúncio.

Os nomes das empresas não são prova de integração: iFood, Itaú, Sicoob, Santander e CAIXA, assim como Infojobs, Indeed e Empregare, permanecem pendentes de adaptadores e validação de acesso. Podem existir anúncios dessas empresas no agregador Gupy, mas isso não representa acompanhamento completo de seus processos. A página pública do iFood respondeu 403 nesta análise; nenhum bloqueio foi contornado. A antiga página Greenhouse do Nubank não é usada, porque o site oficial passou a apontar para Ashby.

## Executar

Um ciclo, com gravação transacional no banco configurado em `ingestion/.env`:

```powershell
python ingestion/scrapers/job_scraper.py --max-jobs 20
```

Somente APIs públicas (sem Chromium/RSS), sem alterar a configuração salva:

```powershell
python ingestion/scrapers/job_scraper.py --api-only --max-jobs 20
```

Modo contínuo: mantém um processo ativo, com intervalo de 30 minutos após cada ciclo. Ctrl+C encerra. Precisa de um host ligado; não é um serviço instalado no Windows nem um agendamento do Supabase:

```powershell
python ingestion/scrapers/job_scraper.py --watch --interval-seconds 1800 --max-jobs 20
```

Para atualizar apenas o snapshot do frontend, sem gravar no banco:

```powershell
python ingestion/scrapers/job_scraper.py --export-json frontend/data/jobs.json --max-jobs 20
```

Docker: `docker compose --profile ingestion run --rm scraper --watch --interval-seconds 1800 --max-jobs 20`. O volume `scraper-cache` preserva os limites entre reinícios. Docker não foi executado nesta máquina.

## Integridade e limites

- Cache em `.cache/ingestion`, fora do Git; frequência mínima também vale para ciclos manuais, exportação e modo contínuo. Remotive pode ser desabilitada por `SCRAPER_REMOTIVE_ENABLED=false`; Nubank por `SCRAPER_NUBANK_ENABLED=false`.
- Lock de arquivo impede ciclos de coleta simultâneos nesta instalação. A gravação usa advisory lock transacional no PostgreSQL, timeout e rollback do lote em falhas. Não alteramos a estrutura do banco nesta etapa.
- Jobs são deduplicados por URL normalizada dentro do ciclo, removendo parâmetros de rastreamento. No banco permanece a identidade existente por fonte e URL; publicações em portais distintos não são fundidas só por cargo/empresa, para não misturar vagas diferentes.
- Falha em uma fonte não impede as demais. Última resposta válida pode ser reutilizada por até sete dias, com status explícito `stale`; depois não é retornada pelo cache. Consultas vazias respeitam o intervalo, e erros têm espera antes de tentar novamente.
- Snapshot é substituído atomicamente e somente após coletar alguma vaga real; falha total preserva o arquivo anterior. `collectedAt` identifica o ciclo; `sources[].checkedAt` identifica a resposta de cada fonte. O painel conectado ao banco informa que são dados armazenados, sem afirmar consulta em tempo real.
- Não há exclusão automática de vagas antigas no banco; confirme a disponibilidade na origem. Marcação de encerramento só deve ser adicionada com paginação/cobertura completa, pois uma vaga ausente da amostra não significa encerramento.
- Mais de uma instalação com caches separados pode somar requisições ao mesmo provedor. Em produção, use uma instância do coletor por cache/ambiente.

## Referências verificadas em 16/10/2026

- [API pública da Remotive e suas regras](https://github.com/remotive-com/remote-jobs-api/blob/main/README.md): atribuição e link originais, listagem pública sem exigir cadastro, até quatro consultas por dia recomendadas, atraso de 24h.
- [Carreiras Nubank](https://nu.com/pt/carreiras): link “Buscar vagas” direciona para [Ashby Nubank](https://jobs.ashbyhq.com/nubank).
- [API pública de vagas Ashby](https://developers.ashbyhq.com/docs/public-job-posting-api): leitura de anúncios publicados e indicação `isListed`.

## Próxima etapa

PWA e push dependem de HTTPS no ambiente público, consentimento do usuário, registro por dispositivo e deduplicação de alertas. O atual `first_seen` tem precisão de dia e não deve ser apresentado como horário exato da publicação. Será necessário registrar descoberta com horário e estabelecer uma linha inicial para não notificar todas as vagas antigas ao ativar push.
