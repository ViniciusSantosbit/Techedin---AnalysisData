# Publicação com SMTP desativado

Status: preparação local. Nenhum serviço foi contratado ou publicado. A hospedagem da API ainda não foi escolhida. Não é necessário comprar domínio próprio para usar os endereços fornecidos pelos serviços enquanto o envio de e-mail estiver desativado.

## 1. Publicar a API Java

Use um serviço compatível com Docker. No Render, por exemplo: crie um **Web Service** vinculado ao repositório, selecione Docker, defina a pasta raiz como `backend` e use o Dockerfile dessa pasta. Confira o preço e a disponibilidade do plano no painel antes de confirmar. Use `/api/auth/session` como caminho da verificação HTTP: sem autenticação deve responder 200 com `authenticated=false`.

A API respeita a variável `PORT` do provedor e usa 8080 localmente. Configure as variáveis pelo painel privado da hospedagem:

| Variável | Valor |
| --- | --- |
| `DB_URL` | URL JDBC do Supabase: `jdbc:postgresql://SEU_HOST:6543/postgres?sslmode=require&currentSchema=public&connectTimeout=10&prepareThreshold=0` |
| `DB_USER` | Usuário do banco configurado no projeto |
| `DB_PASSWORD` | Senha do banco, somente no painel privado |
| `SPRING_FLYWAY_ENABLED` | `false` para o Supabase atual, administrado manualmente e já com V1–V7 |
| `ACCOUNT_REQUIRE_VERIFICATION` | `false` |
| `ACCOUNT_MAIL_MODE` | `disabled` |
| `SESSION_COOKIE_SECURE` | `true` no HTTPS publicado |
| `CORS_ALLOWED_ORIGINS` | `https://techedin.vercel.app` ou a origem real fornecida pela Vercel |
| `APP_PUBLIC_URL` | `https://techedin.vercel.app/` ou a URL real do site |
| `GEMINI_API_KEY` | Chave do Gemini, somente no backend |
| `GEMINI_MODEL` | Mesmo modelo já configurado e validado no ambiente local |
| `WEB_PUSH_ENABLED` | `false` inicialmente; habilite após validar o site HTTPS |

O backend publicado não lê automaticamente `ingestion/.env`: copie somente as variáveis necessárias para o painel. Não coloque a senha dentro da URL JDBC nem publique arquivos `.env`. Os arquivos `.dockerignore` excluem os segredos locais dos contextos de build.

Não habilite Flyway sobre o Supabase manual sem planejar o histórico de migrations. Em um banco novo, mantenha Flyway habilitado para criar o schema completo. Não use `ddl-auto=create` ou `create-drop` no banco atual.

Inicie com uma instância da API: as sessões são mantidas na memória do processo e reinicializações encerram os logins. Várias instâncias exigiriam armazenamento de sessões compartilhado. Confirme a memória disponível para Java e o comportamento do plano quando o serviço fica ocioso.

O limitador de autenticação usa o endereço direto da conexão. Atrás da Vercel/proxy, vários usuários podem compartilhar esse endereço. Antes de abrir ao público, validar esse comportamento e configurar um limite adequado no proxy; não confiar indiscriminadamente em cabeçalhos enviados pelo cliente.

## 2. Conectar e publicar o frontend na Vercel

Depois que a API fornecer uma URL HTTPS, execute na raiz do projeto, substituindo o exemplo pela URL real:

```powershell
python tools/configure_vercel_api.py --api-url https://SUA-API.example.com
```

O comando altera somente o proxy `/api/:path*` em `frontend/vercel.json`, preservando os cabeçalhos e outras rotas. A origem escolhida receberá as chamadas autenticadas do site; use apenas a sua API. Nenhum endereço fictício foi ativado no arquivo atual.

Na Vercel, importe o repositório e use:

- Root Directory: `frontend`.
- Framework: Other.
- Build Command: vazio, pois o site é estático.
- Output Directory: `.`.

Publique após revisar a alteração do proxy. O navegador usará `/api/...` no mesmo domínio do frontend. Não configure uma URL externa diretamente no JavaScript para os fluxos de sessão. O endereço gratuito fornecido pela Vercel é suficiente para essa etapa.

Valide pela URL pública: painel, cadastro/login, preferências, upload/avaliação do currículo, chat e logout. Teste que recuperação/reenvio de e-mail continuam ocultos. Testes locais não confirmam a configuração dos cookies no proxy real; esse teste precisa ocorrer depois da publicação.

## 3. Publicar o coletor

O coletor usa outro processo/container, a partir da pasta `ingestion`. Ele não é um servidor HTTP. Utilize um worker em segundo plano com o comando do container:

```text
python scrapers/job_scraper.py --watch --interval-seconds 1800
```

Configure `DB_HOST`, `DB_PORT=6543`, `DB_NAME=postgres`, `DB_USER`, `DB_PASSWORD` e `DB_SSLMODE=require` no worker. O cache fica em `/app/.cache/ingestion`; use disco persistente se o serviço oferecer e execute somente uma instância. O bloqueio de coleta é local e não coordena réplicas em hosts diferentes. Reiniciar sem cache pode antecipar consultas às fontes.

Sem esse processo ativo, o painel mantém a amostra existente, mas novas vagas deixam de ser coletadas. Alertas push também dependem da API ativa: siga [PWA_PUSH.md](PWA_PUSH.md) para as variáveis VAPID e o teste em celular. Não confunda a existência de anúncios no banco com coleta contínua já publicada.

Referências: [Render com Docker](https://render.com/docs/docker), [web services](https://render.com/docs/web-services), [workers](https://render.com/docs/background-workers), [proxy na Vercel](https://vercel.com/docs/routing/rewrites).
