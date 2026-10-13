# Implementação — 12/10/2026

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
