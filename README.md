# Techedin — inteligência de mercado para vagas de TI

Pipeline Python → PostgreSQL → Spring Boot/Java 21 → HTML/CSS/JavaScript e Chart.js.
As estatísticas contam **vagas que citam uma tecnologia**, não repetições da palavra na descrição.

## Ver vagas reais no painel sem Docker ou banco

Na raiz do projeto, execute:

```powershell
.\tools\collect_real.ps1 -MaxJobs 20
python -m http.server 8081 --directory frontend --bind 127.0.0.1
```

Abra http://localhost:8081 e recarregue a página com Ctrl+F5. O coletor consulta os portais reais e salva `frontend/data/jobs.json`. O site lê esse arquivo quando a API não está disponível ou ainda não tem vagas.
O arquivo contém data da coleta, origem, links públicos, descrições, tecnologias e estado de cada fonte. Não há substituição por vagas fictícias em caso de falha. Uma coleta vazia ou com erro mantém o último arquivo válido.
**Atualizar painel** relê os dados disponíveis; para buscar novas vagas nos portais, execute o coletor novamente.
Esse modo funciona sem credenciais Supabase. A integração com o banco continua disponível quando `ingestion/.env` for configurado.

## Execução local com Docker

Requisitos: Docker Engine/Desktop com Compose. Na raiz:

```sh
docker compose up --build -d db backend frontend
```

Abra http://localhost:8081. A API fica em http://localhost:8080.
O backend executa as migrations Flyway automaticamente em um banco novo.
A primeira execução cria o catálogo, sem vagas fictícias. O gráfico fica vazio até a coleta.
Depois que a API responder:

```sh
docker compose --profile ingestion run --rm scraper --max-jobs 10
```

O scraper é um processo separado. Retorno não zero indica ausência de vagas ou falha de gravação. Quando apenas uma fonte falha, as vagas válidas são preservadas e a coleta parcial é informada nos logs e no snapshot.
Volumes preservam o banco. Não use `down -v` se quiser manter os dados.
As portas são publicadas apenas no computador local; este Compose é para desenvolvimento.

## Execução sem Docker

Requisitos: Java 21, Python 3.12+ e PostgreSQL 16. Crie um banco `techedin` vazio.
No PowerShell, para o backend:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/techedin'
$env:DB_USER = 'postgres'
$env:DB_PASSWORD = 'sua-senha-local'
cd backend
.\mvnw.cmd spring-boot:run
```

Em outro terminal, na raiz:

```powershell
python -m pip install -r ingestion/requirements.txt
python -m playwright install chromium
Copy-Item ingestion/.env.example ingestion/.env  # apenas se ainda não houver .env
# Edite ingestion/.env com as credenciais do mesmo banco utilizado pelo backend.
python ingestion/scrapers/job_scraper.py --max-jobs 10
python -m http.server 8081 --directory frontend --bind 127.0.0.1
```

Abra http://localhost:8081. Sirva o frontend por HTTP: os scripts usam módulos ES.
Variáveis do processo têm precedência sobre `.env`. Spring Boot não carrega o `.env` do Python.
Para outra URL de API, defina `window.TECHEDIN_API_BASE` antes de carregar `js/app.js`.
Para outra origem, configure `CORS_ALLOWED_ORIGINS` no backend (lista separada por vírgulas).

## Banco já existente

Faça backup e examine o schema antes das mudanças. Se as tabelas antigas foram criadas manualmente e não existe `flyway_schema_history`, a inicialização falha para evitar assumir uma versão incorreta.
Se o schema corresponder à V1 original, execute **uma única vez** o backend com:

```powershell
$env:SPRING_FLYWAY_BASELINE_ON_MIGRATE = 'true'
$env:SPRING_FLYWAY_BASELINE_VERSION = '1'
.\mvnw.cmd spring-boot:run
```

Isso marca V1 como existente e aplica V2/V3. Remova essas variáveis nas próximas execuções.
Se houver versões Flyway anteriores ou alterações incompatíveis, reconcilie o histórico e schema antes; não use `repair` sem analisar as diferenças.
V3 preserva vagas antigas, remove a unicidade por título/empresa e adiciona identidade por origem/URL.
Registros antigos ficam `is_verified=false`, fora das análises: suas classificações antigas não são confiáveis.
Novas coletas geram registros identificados por URL; os antigos permanecem para auditoria.

SQL canônicos: `database/migrations`. Cópias em `backend/src/main/resources/db/migration` são empacotadas no JAR.
`python tools/check_migrations.py` verifica sincronização. O seed separado é uma cópia de V2; não execute ambos como migrations independentes.

## Coleta e qualidade dos dados

- `ingestion/technologies.json` contém nomes canônicos e aliases, sem IDs do banco.
- IDs são consultados por nome. Tecnologia ausente no banco aborta a transação.
- Identidade da vaga: `(source, source_url)`; títulos iguais não fundem URLs diferentes.
- A coleta visita os detalhes, limita URLs por fonte e insere pausa entre requisições.
- Senioridade vem do título; sem correspondência, usa `Não informado`.
- Publicação vem de `time` ou texto rotulado. Sem data, usa a primeira coleta; publicação continua nula.
- Reprocessar substitui os fatos: não acumula contagem nem tecnologias removidas.
- Consultas excluem demo e registros legados. `is_verified` significa pipeline corrigido, não revisão humana.
- O gráfico mostra publicação ou primeira coleta. Não representa histórico de vagas ativas nem amostra completa do mercado.

Seletores dependem do HTML dos portais. Bloqueios/login/mudanças podem impedir coleta; a falha é registrada e nenhuma vaga é inventada para compensar.
O filtro Gupy usa `/job-search/term=desenvolvedor`; URLs antigas com `?term=` são normalizadas automaticamente.
Na verificação de 12/10/2026, a Gupy forneceu uma vaga real com tecnologias; a Catho respondeu HTTP 403. A coleta completa de ambas as fontes continua dependendo da disponibilidade dos portais.
Não há paginação completa. Aliases curtos/ambíguos exigem avaliação com amostra real antes de decisões.

## Dados de demonstração para desenvolvimento

```sh
python ingestion/scrapers/job_scraper.py --demo-json demo-jobs.json
```

Gera um arquivo separado para desenvolvimento. O painel principal aceita somente `dataKind=real` e vagas com `is_demo=false`; não tem carregador de demonstração. O repositório Python também rejeita `is_demo=true` antes de abrir conexão.

## API

| Método e caminho | Resultado |
|---|---|
| `GET /api/technologies` | Catálogo |
| `GET /api/technologies/trends` | Contagem por data/tecnologia |
| `GET /api/technologies/{name}` | Lista com um resumo: total geral e senioridade predominante; ignora caixa do nome |
| `GET /api/analytics/seniority` | Contagem por senioridade/tecnologia |
| `GET /api/dashboard` | Amostra de até 2.000 vagas reais verificadas, com empresas, links, datas, senioridades e tecnologias |

Tecnologia inexistente: 404. Tecnologia sem vagas: métricas zeradas.
Empate na senioridade predominante: ordem alfabética do nível.

## Disparo administrativo e agendamento

Endpoint opcional, **desativado por padrão**. Para backend local com Python instalado:

```powershell
$env:SCRAPER_ADMIN_ENABLED = 'true'
$env:SCRAPER_ADMIN_TOKEN = 'configure-um-token-aleatorio-de-pelo-menos-32-caracteres'
$env:SCRAPER_SCRIPT = 'C:\caminho\Techedin---AnalysisData\ingestion\scrapers\job_scraper.py'
# Configure DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD/DB_SSLMODE para o Python.
```

`POST /api/admin/scraper/trigger` exige `X-Admin-Token`, retorna 202 e roda em background.
`GET /api/admin/scraper/status`, com o mesmo token, informa `idle/running/completed/failed/timeout/interrupted`.
Token inválido: 401. Coleta simultânea: 409. Limite: 10 URLs por fonte e cinco minutos.
O cliente não fornece comandos ou URLs no POST. Use HTTPS se expuser a API.
O container backend não inclui Python: mantenha o endpoint desativado nessa stack e execute `scraper` separadamente.

Para recorrência, configure cron/Agendador de Tarefas no destino com o comando do scraper, capturando logs/código de saída. Nenhum agendamento foi instalado automaticamente.

## Testes e CI

```sh
python tools/check_migrations.py
python -m unittest discover -s ingestion/tests -v
node --test frontend/tests/*.test.mjs
cd backend
./mvnw -Pintegration verify
```

No Windows use `.\mvnw.cmd -Pintegration verify`.
Testes Python de PostgreSQL usam `TEST_DB_DSN` e criam/removem somente um schema temporário por teste; sem a variável ficam ignorados.
O teste Java de migrations usa Testcontainers; sem Docker fica ignorado.
Os demais verificam parsing, bloqueio de demo, consultas H2, agregações, séries e autenticação administrativa.
`python tools/check_frontend.py` valida o painel no Chromium usando `frontend/data/jobs.json` real (requer acesso ao CDN do Chart.js).
`python tools/check_sources.py` verifica os seletores nos portais sem gravar no banco.
GitHub Actions fornece PostgreSQL, Chromium e Docker, executa testes e constrói imagens/JAR.
Deploy não habilitado: faltam provedor, ambiente e secrets.

## Credenciais

`.env` fica fora do Git. `application.properties` contém referências a variáveis e defaults locais.
`target/` foi retirado do índice Git sem apagar arquivos locais.
Se houve senha Supabase em commits anteriores, **rotacione-a no painel Supabase** e atualize os ambientes. Arquivos atuais não revogam senha exposta nem apagam o histórico.
Para Supabase use `DB_SSLMODE=require` e JDBC com SSL. Copie host/usuário/porta do painel; não há host Supabase fixo no código.
