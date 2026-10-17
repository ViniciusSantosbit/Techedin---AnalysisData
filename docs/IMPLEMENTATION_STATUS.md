# Estado da implementação

## 16/10/2026 — Upload e revisão de currículo

- Adicionado upload privado de PDF/DOCX na seção **Seu currículo**, dentro de **Meu perfil**. É possível substituir o documento, revisar/salvar o texto e excluir o currículo. O cabeçalho do modal fica acessível durante a rolagem.
- Somente texto e metadados são retidos. Arquivo original é descartado após a requisição. Esta etapa não envia dados ao Gemini nem atribui nota ao currículo.
- Limites: 5 MB por arquivo, 20 páginas PDF, 100.000 caracteres, 8 MB DOCX descompactado, 256 entradas ZIP e 15 segundos de extração com um worker sem fila. Arquivos protegidos, sem texto, com formato falso, DTD, entidades externas ou macros não são aceitos.
- Endpoints privados em `/api/account/resume`, protegidos por sessão e CSRF. SQL sempre usa o usuário da sessão; UUID de revisão impede sobrescrita entre abas. Arquivo inválido preserva o currículo anterior.
- V5 adiciona somente `app_user_resume`, com FK de exclusão em cascata, RLS e bloqueio de acesso público/Data API. Inspeção somente leitura do Supabase encontrou essa tabela já existente, com colunas esperadas, RLS ativo, sem políticas públicas ou permissões para `anon`/`authenticated`. SQL não foi reaplicado e nenhum currículo real foi lido ou alterado.
- Perfil Maven `runtime` separa artefatos do editor em `target/runtime`. Corrige interferência observada em `target/classes` que impedia descoberta de testes/inicialização de classes.
- Validação: 20 testes Java, 9 testes dos utilitários Python e 6 Node passaram. Chromium validou cadastro/preferências mais upload, revisão persistida, remoção e logout em desktop/celular com dados isolados. O teste de migrations em PostgreSQL/Testcontainers ficou ignorado por ausência de Docker; a estrutura/proteção real da V5 foi conferida sem escrita no Supabase.
- API atualizada iniciada com `-Pruntime` e schema manual validado. Verificação HTTP real com origem `127.0.0.1:5500`: painel e consulta de sessão 200; currículo sem login 401 com CORS correto. Não foram criados usuários ou currículos de teste no Supabase.
- Próxima etapa: análise do texto com Gemini, avaliação explicável de 0 a 10 e perfil estruturado para recomendações.

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

## Avaliação Gemini — 16/10/2026

Implementados botão de avaliação, endpoint autenticado com CSRF, cinco critérios somados de 0 a 10, resposta estruturada validada, limites de concorrência/frequência e detecção de revisão alterada. Sem nova migração. Nota somente na tela; desaparece ao editar/reabrir. Chave privada GEMINI_API_KEY ainda não configurada: chamadas reais pendentes. Validados 23 testes Java e interface desktop/móvel com nota simulada. Nenhum currículo real foi enviado nos testes.

## Assistente de carreira e navegação — 16/10/2026

Currículo separado do perfil. Botão visível somente após login; saudação com nome e restauração da sessão no carregamento. Gemini configurado localmente; consulta de metadados confirmou acesso ao modelo.

Adicionado chat flutuante com raio, disponível após login, foco em currículo/candidaturas/entrevistas, histórico temporário e inclusão opcional do currículo. Endpoint privado com CSRF, validação de tamanho/papéis, limitação de frequência/concorrência e saída tratada como texto. Não exige migration. PWA/push e recomendações personalizadas permanecem etapas futuras.

Validação do chat: 26 testes Java passaram; Chromium validou envio simulado, saída como texto, limpeza, Escape, login/logout e dimensões móveis. Não foram enviados currículos reais durante os testes.

### Contexto automático do currículo

A pedido do usuário, o chat passou a usar automaticamente o currículo salvo na conta, sem checkbox ou novo upload. Busca sempre pela identidade autenticada. Mudanças de revisão invalidam o histórico enviado à IA, inclusive quando feitas em outra aba; remoção retira o currículo das próximas consultas. O texto é contexto de cada pergunta, não treinamento permanente do modelo. Aviso de envio ao Gemini atualizado na interface.

## Sequência aprovada: recomendações → fontes/coleta → PWA/push

Primeira entrega: recomendações privadas na página, por tecnologias citadas no currículo e nas vagas reais verificadas, cargo desejado e senioridade escolhida. Motivos e tecnologias restantes exibidos; até 20 resultados na amostra de 2.000. Não utiliza nota do currículo como critério de contratação. Não infere senioridade pela idade/anos, localização ou modelo de trabalho. Currículo alterado/excluído e preferências salvas atualizam a lista. Nenhuma migration ou envio adicional ao Gemini.

Próxima entrega: ampliar fontes e atualizar a coleta. Pesquisa da documentação oficial da Remotive identificou atraso de 24h na API pública e recomendação de no máximo quatro consultas por dia, portanto essa fonte não pode prometer alerta instantâneo. PWA/push depende depois de deduplicação e controle da descoberta de novas vagas.

Validação das recomendações: 30 testes Java passaram. Consulta SQL validada adicionalmente em H2 com currículo sintético, filtrando vagas demo/não verificadas. Interface desktop/móvel validada com API simulada, incluindo atualização após upload e ocultação ao sair. Catálogo e migrations sincronizados. Nenhum currículo real foi consultado nos testes e nenhum dado foi gravado no Supabase.

## Fontes adicionais e coleta — 16/10/2026

Implementadas Remotive (API pública, categoria software-dev, atraso de 24h) e Nubank via Ashby, identificado como canal atual pelo site oficial. APIs limitadas a anúncios públicos com título/descrição/link válido e tecnologias do catálogo. Anúncios Ashby não listados são ignorados; data vem de publishedAt, não updatedAt. Gupy e Catho com URLs antigas passam a usar buscas efetivas.

Cache por fonte: Remotive 6h, Nubank e WWR 30min, páginas Gupy/Catho 15min. Bloqueio de coleta simultânea no mesmo cache, espera após falhas, reaproveitamento de resposta válida por até sete dias com status anterior explícito. Falha total preserva snapshot anterior. Modo --watch implementado; nenhum serviço/agendamento automático foi instalado.

Persistência: advisory lock transacional, timeouts, upsert por identidade existente, deduplicação no ciclo e inserção de fatos em lote com reutilização de dimensões. Schema e dados de conta/currículo não foram alterados. Docker preparado com cache persistente e permissões, mas indisponível para execução nesta máquina.

Validação: 23 testes Python de coleta executados, 21 passaram e 2 PostgreSQL foram ignorados por ausência de TEST_DB_DSN; 7 testes Node passaram. Chromium confirmou conta/currículo/recomendações e indicação de cache/atraso na interface desktop/móvel. APIs reais validadas primeiro sem banco; ciclo completo exportou 40 vagas. Importação e reimportação concluídas no Supabase: 40 vagas e 410 fatos, com Catho respondendo 403. Nenhum currículo foi lido/enviado durante estes testes.

Demais empregadores/portais continuam pendentes de adaptadores próprios, conforme docs/JOB_SOURCES.md. Próxima parte: PWA/push, com HTTPS, consentimento, subscriptions por dispositivo, horário de descoberta e prevenção de alertas retroativos/duplicados.
