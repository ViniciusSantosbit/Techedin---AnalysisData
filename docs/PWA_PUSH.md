# PWA e alertas de vagas

## Zoom no app instalado

Em modo standalone com tela de toque, o frontend aplica limite de escala e `touch-action: pan-x pan-y`, bloqueia gestos de pinça canceláveis e mantém a rolagem com um dedo. Campos têm fonte mínima de 16 px nesse modo para evitar zoom automático ao receber foco. A mesma regra é carregada pela tela offline. Abas comuns do navegador, inclusive após clicar em instalar, mantêm o zoom livre; o bloqueio só entra quando o app é aberto em modo instalado.

A detecção e as regras foram verificadas com testes automatizados e emulação. A emulação não confirmou o gesto nativo de pinça; é necessário validar em aparelhos físicos. Navegadores, especialmente Safari/iOS, e configurações de acessibilidade podem ignorar limites de escala. A aplicação não controla a ampliação do sistema operacional.

## Ativação no Render

A API foi publicada em `https://techedin-analysisdata.onrender.com`. Configurar `ingestion/.env` no computador não configura o ambiente do Render.

No painel do serviço, abra **Environment**, edite as variáveis e salve para gerar um novo deploy:

| Variável | Valor |
| --- | --- |
| `WEB_PUSH_ENABLED` | `true` |
| `WEB_PUSH_PUBLIC_KEY` | Copiar o valor existente em `ingestion/.env` |
| `WEB_PUSH_PRIVATE_KEY` | Copiar o valor existente em `ingestion/.env`; manter secreto |
| `WEB_PUSH_SUBJECT` | URI HTTPS de contato do projeto, por exemplo `https://techedin-analysisdata.onrender.com` |

Preserve o mesmo par de chaves entre deploys. Depois do deploy, atualize o site, entre na conta e ative os alertas no perfil. A API e o coletor precisam estar ativos para descobrir e avisar novas vagas; uma instância suspensa por inatividade não executa esse trabalho. SMTP não participa do Web Push.

## Implementação e uso local

Implementados manifest, ícones PNG, instalação com orientações Android/iOS, service worker, tela offline e preferências de push por dispositivo. A API cadastra inscrições privadas, verifica novas vagas com o mesmo motor de recomendações e envia Web Push autenticado por VAPID.

No computador, abra pelo Live Server ou Docker em localhost/127.0.0.1. O botão **Instalar app** usa a opção nativa do navegador quando disponível; senão mostra instruções. Para alertas, entre na conta, anexe o currículo e abra o perfil → **Alertas neste dispositivo** → **Ativar notificações**. Somente esse clique solicita permissão. Desativar remove a inscrição deste navegador; sair da conta tenta remover a inscrição e cancela a assinatura no navegador. Outros dispositivos mantêm suas próprias preferências. Máximo: cinco dispositivos por conta. A permissão também pode ser revogada nas configurações do navegador/sistema.

## Privacidade e comportamento

- Cache offline restrito a ícones, manifest e página de indisponibilidade. Nem index.html, APIs, sessão, currículo, recomendações privadas ou chat são guardados no Cache Storage. O site precisa de conexão para consultar dados atualizados.
- Banner genérico que abre a seção de sugestões. O push não inclui texto de currículo, nome de usuário nem descrição de vaga. Mensagem sem payload, autenticada por VAPID; a assinatura JWT ES256 usa Nimbus JOSE/JWT, com chaves P-256 locais estáveis. Nenhuma criptografia de payload personalizada foi implementada. O service worker sempre apresenta uma notificação visível e nunca segue links de payload externo.
- Endpoints de inscrição são capacidades privadas: ficam em tabela com RLS, sem acesso por anon/authenticated da Data API. A API usa sessão, CSRF, limite de tamanho e lista explícita de provedores HTTPS (Google/Chrome, Mozilla/Firefox e Apple/Safari). Não segue redirecionamentos do provedor. Somente o proprietário consulta/remove a própria inscrição; inscrições não são transferidas entre contas.
- Ativação registra um cursor inicial no maior ID existente. Vagas antigas não geram uma enxurrada de alertas. `discovered_at` registra descoberta no pipeline, sem fingir ser o horário da publicação. Reprocessar uma URL existente não muda esse horário. A coleta deve continuar usando o writer serializado existente.
- O dispatcher verifica até 25 dispositivos por ciclo (a cada minuto após a conclusão do ciclo), com lotes de 100 IDs, lease para evitar concorrência, reavaliação do currículo/preferências nas tentativas e índice de correspondência de pelo menos 40/100. Esse índice é cobertura de tecnologias + cargo + senioridade, não probabilidade de contratação. Localização e modelo de trabalho não entram enquanto não houver dados consistentes de todas as fontes.
- Descobertas anteriores à ativação ou com mais de 24h não entram nos alertas. Sem currículo ou correspondência, o cursor avança sem notificar. Corrigir o currículo não dispara alertas retroativos.
- Sucesso avança o cursor. Falhas têm até três tentativas com espera; respostas 404/410 removem inscrições expiradas. Novos avisos são agrupados, com intervalo mínimo de 15 minutos por dispositivo e TTL de uma hora no provedor. Não há garantia de entrega ou de prazo: dependem de fonte, servidor, provedor, rede e permissões do aparelho. Se houver queda após o provedor aceitar e antes de registrar sucesso, uma repetição ainda é possível; tag/Topic comuns ajudam a agrupar os avisos.
- O processo da API precisa ficar ativo para enviar e o coletor precisa ficar ativo para descobrir vagas. O modo --watch está disponível em docs/JOB_SOURCES.md; nenhum serviço do Windows/agendamento foi instalado automaticamente. A Remotive continua com atraso de 24h e intervalo mínimo de seis horas.

## Configuração

Migration canônica: `database/migrations/V6__private_push_subscriptions.sql`, com cópia empacotada no backend. Em banco manual, `python tools/apply_push_migration.py` apenas inspeciona; `--apply` executa somente a V6 em transação e valida RLS/permissões. Em bancos Flyway, use o fluxo normal de migrations. Não edite uma V6 já aplicada.

Geração local, sem exibir a chave privada:

```powershell
python tools/configure_push.py --subject https://techedin.vercel.app --enable
python tools/start_backend.py --manual-schema
```

As chaves são salvas em `ingestion/.env`, ignorado pelo Git. O gerador preserva um par já existente. Guarde as chaves entre reinícios e deploys; trocá-las exige renovar inscrições. `WEB_PUSH_ENABLED=false` desabilita novos cadastros/envios. Não exponha `WEB_PUSH_PRIVATE_KEY` no frontend ou nas variáveis VITE/NEXT_PUBLIC. Configurações Docker precisam ser passadas ao container do backend, mantendo o segredo no ambiente de execução.

## Publicação pendente

1. Publicar o frontend, com raiz `frontend`, e manter manifest/ícones/sw.js acessíveis na mesma origem. `frontend/vercel.json` prepara headers dos assets, mas não publica nada.
2. Publicar a API Java em um host HTTPS que permita processo/scheduler contínuo. Um deploy apenas estático na Vercel não executa esse backend ou o coletor.
3. Configurar um proxy do domínio do frontend em `/api/*` para a API real; a URL ainda não foi definida. Essa mesma origem preserva cookies HttpOnly/SameSite=Lax. `frontend/nginx.conf` contém proxy para o serviço backend do Compose. Na Vercel, a rewrite dependerá do endereço real da API.
4. No backend publicado, configurar `CORS_ALLOWED_ORIGINS=https://techedin.vercel.app`, `SESSION_COOKIE_SECURE=true`, dados do banco, Gemini e chaves VAPID; não copiar o arquivo .env para o frontend. Manter o coletor ativo com cache persistente.
5. Fazer teste manual em Android e iPhone com permissão concedida e app em segundo plano. No iPhone/iPad, instalar na Tela de Início e abrir pelo ícone; suporte a Web Push exige iOS/iPadOS 16.4+. A entrega real por FCM/APNs e a instalação física no celular ainda não foram verificadas.

## Verificação local

- `node --test frontend/tests/*.test.mjs`: assets, privacidade do worker, banner e destino fixo.
- `python tools/check_pwa.py`: worker real no Chromium, instalação orientada, tela offline e ausência de cache privado.
- `python tools/check_account_frontend.py`: conta, currículo, recomendações e consentimento/ativação/desativação com inscrição simulada, sem inscrição ou push real.
- Maven: assinatura VAPID, isolamento entre contas, CSRF, cursor sem retroativos, reavaliação nas tentativas e remoção de inscrições expiradas. Integração de migrations PostgreSQL/Testcontainers depende de Docker.

Referências: [WebKit: Web Push iOS](https://webkit.org/blog/13878/), [RFC 8292: VAPID](https://www.rfc-editor.org/rfc/rfc8292.html), [RFC 8030: Web Push](https://www.rfc-editor.org/rfc/rfc8030.html).
