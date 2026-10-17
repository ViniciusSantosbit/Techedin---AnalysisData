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
.\mvnw.cmd -Pruntime spring-boot:run
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

### Supabase com SQL executado manualmente

Aplicar a V4 no SQL Editor não inicia a API Java. Se o navegador mostrar `ERR_CONNECTION_REFUSED` em `localhost:8080`, confira o backend e sua conexão. O arquivo `ingestion/.env` pode continuar com os valores locais do exemplo, mesmo após criar as tabelas no Supabase.

Em **Connect** no painel Supabase, copie host, porta, banco e usuário da conexão PostgreSQL. Para rede somente IPv4, use **Session pooler** e os valores exatos apresentados, normalmente porta 5432. Preencha `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` e `DB_SSLMODE=require` no arquivo local `ingestion/.env`. `DB_HOST` recebe apenas o host; `DB_PASSWORD` recebe a senha original do banco. Não coloque a senha no JavaScript nem no Git.

Com V1–V5 já aplicadas manualmente, execute na raiz:

```powershell
python tools/check_database.py
python tools/start_backend.py --manual-schema
```

O primeiro comando inspeciona somente estrutura, histórico Flyway e estado de RLS, sem imprimir credenciais nem alterar dados. O segundo carrega a configuração do `.env`, verifica as tabelas/colunas e inicia o backend, mantendo Flyway desativado apenas nesse processo. Não registra artificialmente a V4 no histórico e não reaplica SQL manual. Para o pooler de transações na porta 6543, o inicializador desativa prepared statements nomeados na conexão JDBC (`prepareThreshold=0`). O terminal precisa continuar aberto; depois que a API iniciar, recarregue o site. Para um banco administrado pelo Flyway, use `python tools/start_backend.py` sem a opção, após conferir o histórico.

Para conferir a V5 sem alterar o banco, execute `python tools/apply_resume_migration.py`. Se o schema é manual e essa migration ainda falta, `python tools/apply_resume_migration.py --apply` cria somente a tabela privada de currículo em uma transação. O comando não altera usuários, vagas ou histórico Flyway e interrompe a operação se detectar histórico gerenciado ou estrutura/proteções incompatíveis.

O inicializador usa o perfil Maven `runtime`, com artefatos em `backend/target/runtime`, para separar a API da compilação incremental do editor. Isso evita falhas de bytecode quando IDE e Maven escrevem simultaneamente nos mesmos arquivos.

A migration V4 acrescenta `app_user` e `app_user_preferences` para contas e objetivos profissionais. Ela não modifica as tabelas analíticas de vagas. É aplicada pelo Flyway na próxima inicialização do backend; faça backup do banco antes de atualizar um ambiente com dados.

Faça backup e examine o schema antes das mudanças. Se as tabelas antigas foram criadas manualmente e não existe `flyway_schema_history`, a inicialização falha para evitar assumir uma versão incorreta.
Se o schema corresponder à V1 original, execute **uma única vez** o backend com:

```powershell
$env:SPRING_FLYWAY_BASELINE_ON_MIGRATE = 'true'
$env:SPRING_FLYWAY_BASELINE_VERSION = '1'
.\mvnw.cmd -Pruntime spring-boot:run
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
- Feeds de programação do We Work Remotely podem ser configurados em `SCRAPER_RSS_URLS`, separados por vírgula. A integração preserva a marca da fonte e o link original da vaga.
- Senioridade vem do título; sem correspondência, usa `Não informado`.
- Publicação vem de `time` ou texto rotulado. Sem data, usa a primeira coleta; publicação continua nula.
- Reprocessar substitui os fatos: não acumula contagem nem tecnologias removidas.
- Consultas excluem demo e registros legados. `is_verified` significa pipeline corrigido, não revisão humana.
- O gráfico mostra publicação ou primeira coleta. Não representa histórico de vagas ativas nem amostra completa do mercado.

Seletores dependem do HTML dos portais. Bloqueios/login/mudanças podem impedir coleta; o feed do We Work Remotely é processado separadamente e sua falha não interrompe a coleta dos portais. Nenhuma vaga é inventada para compensar.
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

## Contas e preferências

O botão **Minha conta** permite criar uma conta, entrar, sair e salvar nome, cargo desejado, região, modelo de trabalho e senioridade. O perfil prepara a próxima etapa de recomendações; ainda não altera a busca de vagas nem envia notificações. O painel público continua acessível sem login.

O recurso exige backend e PostgreSQL configurados. O modo estático com `data/jobs.json` não armazena contas. No desenvolvimento, a interface usa automaticamente o mesmo host (`localhost` ou `127.0.0.1`) na API da porta 8080, preservando os cookies. As origens locais nas portas 5500 e 8081 são permitidas explicitamente no CORS. Uma configuração manual de `window.TECHEDIN_API_BASE` deve manter o mesmo site para autenticação por cookie. Em produção, a API usa a origem do próprio site por padrão; configure o proxy `/api` ou uma URL explícita apropriada.

| Método e caminho | Resultado |
|---|---|
| `GET /api/auth/csrf` | Token CSRF e nome do cabeçalho para operações de escrita |
| `GET /api/auth/session` | Estado da sessão; HTTP 200 com `authenticated=false` e sem perfil quando não há login |
| `POST /api/auth/register` | Cadastro (`email`, `displayName`, `password`) e início da sessão |
| `POST /api/auth/login` | Login com `email` e `password` |
| `POST /api/auth/logout` | Invalida a sessão e remove o cookie |
| `GET /api/account` | Perfil do usuário da sessão; 401 sem login |
| `PUT /api/account/preferences` | Atualiza `displayName`, `targetRole`, `location`, `workModel` e `seniority` da própria conta |

As operações de escrita acima exigem o cabeçalho obtido em `/api/auth/csrf` e cookie da sessão. Reobtenha o token após login, cadastro ou logout. A interface faz isso automaticamente e usa `credentials: include`; não guarda senha nem token de sessão em localStorage. Preferências usam `workModel=any/remote/hybrid/onsite` e `seniority=any/internship/junior/mid/senior/specialist`.

Senhas são protegidas com BCrypt e devem ter de 12 a 64 caracteres, limitadas a 72 bytes UTF-8. Sessões expiram após 30 minutos sem atividade e são encerradas se o backend reiniciar. Há limite inicial por endereço de conexão: 10 cadastros e 30 logins a cada dez minutos, por processo. Atrás de proxy, esse endereço pode ser compartilhado; antes de produção, configure limitação adequada à infraestrutura.

Em produção, use HTTPS, `SESSION_COOKIE_SECURE=true`, origens CORS explícitas e frontend/API no mesmo site. Cookies são HttpOnly e SameSite=Lax. Recuperação de senha, confirmação de e-mail, exclusão de conta e sessões compartilhadas entre servidores ficam para etapas posteriores, antes do lançamento público.

Verificação isolada da interface: `python tools/check_account_frontend.py` (Playwright/Chromium). Usa respostas simuladas e dados fictícios, sem acessar o banco ou autenticar usuários reais.

## Currículo por usuário

Depois de entrar em **Meu perfil**, a seção **Seu currículo** permite anexar, substituir, revisar o texto extraído e remover um currículo PDF/DOCX. São aceitos arquivos de até 5 MB, PDFs sem senha com até 20 páginas e DOCX padrão sem macros. PDFs digitalizados sem texto precisam de OCR prévio; não há OCR nesta etapa.

O PDF é lido com Apache PDFBox 3.0.8. O DOCX é lido com ZIP/XML limitado a 8 MB descompactados e 256 entradas, sem entidades externas nem DTD. A extração aceita até 100.000 caracteres, tem limite de 15 segundos e um processamento por vez; sobrecarga retorna 429 para uma nova tentativa. O arquivo original fica somente no processamento temporário da requisição e não é armazenado pelo aplicativo. Apenas texto, nome, tipo, tamanho e datas são persistidos.

A V5 cria `app_user_resume`, com uma linha por usuário e exclusão em cascata da conta. RLS fica habilitado sem políticas públicas; privilégios de `PUBLIC`, `anon` e `authenticated` são revogados. O backend se conecta como proprietário da tabela ou papel confiável com acesso equivalente e faz o controle de usuário pela sessão. Usuários Supabase Auth e usuários da aplicação Java não são o mesmo sistema de autenticação.

| Método e caminho | Resultado |
|---|---|
| `GET /api/account/resume` | `{resume: null}` ou texto/metadados da própria conta |
| `POST /api/account/resume` | Multipart com `file`; `revision` também é necessário para substituir currículo existente |
| `PUT /api/account/resume` | JSON `{revision, text}` para salvar revisão |
| `DELETE /api/account/resume` | JSON `{revision}` para excluir o texto/metadados |

Todos exigem login; escrita também exige CSRF. O proprietário vem da sessão e não de um ID enviado pelo cliente. Edições, substituições e exclusões usam uma revisão UUID para impedir que outra aba sobrescreva alterações recentes (409). Arquivos inválidos preservam o currículo anterior. Não há envio ao Gemini, avaliação de 0 a 10 ou recomendações automáticas nesta etapa; a extração prepara esses recursos.

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
./mvnw -Pruntime,integration verify
```

No Windows use `.\mvnw.cmd "-Pruntime,integration" verify`.
Testes dos inicializadores/migration manual: `python -m unittest discover -s tools/tests -v`.
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

### Nota do currículo com Gemini

No arquivo local ignorado `ingestion/.env`, configure `GEMINI_API_KEY` com uma chave do Google AI Studio. Opcional: `GEMINI_MODEL` (padrão `gemini-3.8-flash`). Reinicie com `python tools/start_backend.py --manual-schema`. Nunca coloque a chave no frontend ou no Git.

No perfil, salve o texto revisado e clique em **Avaliar currículo com IA**. O texto salvo será enviado ao Google Gemini; confira as condições de tratamento de dados do serviço contratado antes de usar currículos reais. A API calcula a nota de 0 a 10 somando cinco critérios de 0 a 2, valida a resposta e apresenta justificativas e sugestões. A avaliação fica apenas na tela, sendo descartada ao recarregar ou alterar o currículo; não exige outra migração do banco. Limites: uma tentativa por conta por minuto, duas chamadas simultâneas e 45 segundos por chamada. Sem chave ou em caso de falha, nenhum valor fictício é exibido.

### Assistente de carreira

Após o login, o botão de raio abre um chat compacto sobre currículos, candidaturas e entrevistas. Usa a mesma GEMINI_API_KEY no backend, com sessão/CSRF, limite de cinco mensagens por minuto por conta e duas consultas simultâneas. O histórico fica em memória na página (até oito mensagens enviadas como contexto); limpar, sair da conta ou recarregar descarta a conversa local. O currículo salvo é incluído automaticamente e buscado pela identidade da sessão a cada pergunta. Ao mudar ou excluir o currículo, o histórico da versão anterior deixa de ser usado. Mensagens e o currículo salvo são enviados ao Google Gemini; o Techedin não persiste as conversas. As regras de escopo são instruções para o modelo, sujeitas às limitações da IA.

### Recomendações iniciais de vagas

Após login, a seção **Vagas para explorar** compara as tecnologias citadas no currículo salvo com a amostra real do banco (até 2.000 vagas; até 20 sugestões). O catálogo é compartilhado com a coleta e validado por `tools/check_migrations.py`. Só há sugestão quando pelo menos uma tecnologia está em comum. O ranking usa cobertura de tecnologias (até 80 pontos), relação do título com palavras específicas do cargo desejado (15) e senioridade correspondente à preferência (5). Vagas com senioridade explicitamente diferente da desejada são omitidas; senioridade ausente é sinalizada. Os pontos são internos para ordenação, não probabilidade de contratação.

A interface explica correspondências e tecnologias não encontradas no currículo, sem tratá-las automaticamente como requisitos obrigatórios. Localização e modelo de trabalho não são inferidos sem dados confiáveis das fontes. A extração é textual: menção não comprova domínio, e sinônimos fora do catálogo podem não ser identificados. É possível atualizar as sugestões, corrigir o currículo e ajustar a senioridade nas preferências. O endpoint privado `GET /api/account/recommendations` usa apenas o usuário da sessão e não grava nem envia o currículo a outro serviço. Vagas antigas podem estar encerradas: confirme sempre na origem. Sem alteração de schema nesta etapa.

### Fontes adicionais e coleta periódica

Adicionadas APIs públicas da Remotive e do Nubank (Ashby), com cache/frequência por fonte, preservação de resposta anterior em indisponibilidade e modo `--watch`. A Remotive tem atraso de 24h; os anúncios continuam acessíveis na listagem pública. Veja [fontes, limitações e comandos de operação](docs/JOB_SOURCES.md). Não exige migration.
