# Estado da implementação

## 14/10/2026 — Contas, preferências e revisão da primeira fonte adicional

Consulta de sessão: a abertura do modal passou a usar `GET /api/auth/session`, que retorna HTTP 200 com `authenticated=false` quando o visitante não está conectado. Isso elimina o 401 esperado da antiga consulta inicial ao perfil. `GET /api/account` permanece privado. Os 6 testes de autenticação e a verificação da interface passaram; a API foi reiniciada e o Chromium confirmou o novo retorno nos dois hosts locais. Não houve alteração de dados do Supabase.

Correção de acesso pelo Live Server: origens locais `localhost` e `127.0.0.1` nas portas 5500/8081 foram incluídas no CORS. A resolução da URL da API foi centralizada em `frontend/js/api-config.mjs`, mantendo o host da página para cookies SameSite. A API foi reiniciada. Passaram 6 testes de autenticação e 6 testes Node; o Chromium confirmou CORS, preflight e cookie CSRF contra a API real a partir dos dois hosts na porta 5500, sem criação de contas nem escrita no Supabase. A verificação pode ser repetida com `python tools/check_local_cors.py`, com API e frontend local em execução.

Atualização de configuração: usuário informou que executou V4 no Supabase manualmente e forneceu os dados PostgreSQL. A configuração local foi atualizada no arquivo ignorado `ingestion/.env`, com SSL obrigatório. A consulta somente leitura confirmou todas as tabelas/colunas necessárias, ausência de histórico Flyway e RLS habilitado nas duas tabelas de contas. A API foi iniciada com `tools/start_backend.py --manual-schema`, sem reaplicar migrations ou alterar dados. No pooler de transações, a URL JDBC usa `prepareThreshold=0`. Verificação HTTP real: painel 200, CSRF 200 e conta anônima 401 (esperado). Os 6 testes do inicializador passaram. Não foram criadas contas de teste no Supabase.

- Cadastro, login, logout e perfil privado disponíveis pelo botão **Minha conta**. Preferências: cargo, região, modelo de trabalho e senioridade. O painel continua público.
- Spring Security com BCrypt, cookie HttpOnly/SameSite=Lax, rotação de sessão, expiração por inatividade, CSRF e CORS com origens explícitas. Senhas e identificadores de sessão não são armazenados no localStorage.
- Migration V4 cria contas e preferências em tabelas separadas do modelo analítico. Cópia empacotada sincronizada. Nenhuma migration foi aplicada a um banco real nesta etapa.
- Integração RSS do We Work Remotely corrigida para ignorar anúncios sem link ou descrição, links externos e declarações de entidades XML. Uma consulta real ao feed de programação retornou uma vaga válida, sem persistência em banco.
- Validação: 11 testes Java passaram (incluindo 6 de autenticação com HTTP real, cookie, isolamento entre contas, CSRF, CORS e limitação de tentativas); 13 testes Python e 4 Node passaram. Teste Java de migration/PostgreSQL ignorado por ausência de Docker; 2 testes Python/PostgreSQL ignorados por ausência de `TEST_DB_DSN`.
- Fluxo da interface validado no Chromium com API isolada: cadastro, edição/reabertura de preferências, logout, credenciais inválidas, indisponibilidade da conta e telas de computador/celular. As verificações usam dados de teste.
- Próxima etapa: upload e extração de currículo vinculado à conta. Recomendações, avaliação de currículo com IA, push/PWA e chat Gemini ainda não foram implementados.
- Antes do lançamento público: HTTPS/cookie Secure, confirmação de e-mail, recuperação/exclusão de conta, definição de privacidade e limitação de requisições adequada ao provedor. Sessões atuais são locais ao processo e encerram no reinício do backend.

## Entrega de 12/10/2026

## Atualização do painel com coleta real

- Coleta de 12 vagas reais da Gupy exportada em `frontend/data/jobs.json`, incluindo 11 empresas e 48 termos do catálogo tecnológico. A Catho respondeu HTTP 403, registrado na transparência das fontes.
- Painel principal sem carregador de demonstração: ranking com barras, gráfico de uma tecnologia por vez, senioridades, busca, links de vagas e indicação da data real da coleta.
- Novo `GET /api/dashboard` retorna a amostra do banco. Sem banco configurado, o frontend lê o snapshot real da coleta.
- O comando `.\tools\collect_real.ps1 -MaxJobs 20` atualiza o snapshot sem exigir Docker ou credenciais.
- Supabase ainda não configurado pelo usuário; nenhum acesso ou migration foi realizado nesse banco.
- Verificação desta atualização: 5 testes Java, 10 Python e 4 Node passaram; 2 testes Python/PostgreSQL foram ignorados porque o banco de teste não está configurado. O navegador validou o snapshot real, os filtros, o acesso às vagas, a recuperação de falhas e a versão móvel.

O checklist abaixo registra a entrega inicial; os detalhes da atualização acima substituem as referências ao modo demo da interface.

| Item do plano | Entrega | Validação/pendência |
|---|---|---|
| Credenciais | Configuração via ambiente, `.env` ignorado, host Supabase fixo removido, `target` retirado do índice | Rotação no Supabase e revisão do histórico ainda dependem do acesso à conta |
| Demo/fallback | Dez vagas em JSON, modo explícito na interface; repositório rejeita demo | Testado; não há fallback automático para o banco real |
| URLs | Filtro atual da Gupy e compatibilidade com URL antiga | Gupy coletou uma vaga real; Catho retornou 403 |
| Seletores | Fallback por links, filtro de URLs de detalhe, deduplicação, visita à descrição | Testado com Chromium e amostra Gupy |
| Senioridade/data | Parsing e dimensões consultadas dinamicamente; primeira coleta como fallback de data | Testado; sem adivinhar senioridade |
| Trigger administrativo | Token obrigatório, execução em background, limite/timeout e status | Autenticação testada; desativado por padrão |
| Testes | JUnit/Mockito/H2, Testcontainers, Python/PostgreSQL, navegador e Node | 5 testes Java, 9 Python e 2 Node passaram; smoke da interface passou. 3 testes PostgreSQL ignorados por falta de Docker/banco local |
| Docker | Dockerfiles de backend/frontend/scraper, Compose com PostgreSQL e volume | Arquivos preparados; Docker indisponível localmente |
| GitHub Actions | Testes, PostgreSQL, construção das imagens e artefato JAR | Workflow preparado; não executado remotamente nesta sessão |
| README | Instalação, migrations, API, demo, segurança, testes e operação | Concluído |

Correções adicionais: migrations em ordem correta; catálogo sem IDs fixos; identidade por URL; exclusão de dados legados; soma geral e senioridade predominante; gráfico temporal; atualização e recuperação de erros; CORS configurável; chave JPA com igualdade/hash.

O JAR foi empacotado com `mvnw -Pintegration verify`. A interface foi verificada em Chromium, com API simulada, incluindo demo, retorno à API, HTTP 503, recuperação e largura móvel.
Nenhuma migration/coleta foi aplicada ao Supabase. Nenhum commit, push, deploy ou agendamento foi realizado.

Antes de produção: rotacionar credenciais expostas, executar a integração PostgreSQL e o Compose, revisar uma amostra maior da coleta real e definir o ambiente de deploy. A disponibilidade da Catho permanece uma limitação externa; seus dados não são substituídos por dados inventados.
