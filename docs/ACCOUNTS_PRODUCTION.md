# Contas, SMTP e teste local

Atualização: por escolha do usuário, o projeto está com `ACCOUNT_REQUIRE_VERIFICATION=false` e `ACCOUNT_MAIL_MODE=disabled`. Cadastro e login funcionam sem confirmação; recuperação por e-mail e reenvio ficam ocultos. As instruções abaixo são opcionais para reativação futura. Contas sem confirmação continuam marcadas como não verificadas no banco; não foram falsamente confirmadas.

A V7 adiciona confirmação de e-mail, versão de sessão e tokens privados. Não altera senhas nem confirma contas existentes. Bancos administrados manualmente: `python tools/apply_account_migration.py --apply`. Em bancos novos, Flyway aplica V1–V7.

## Teste neste computador

No arquivo ignorado `ingestion/.env`:

```dotenv
ACCOUNT_REQUIRE_VERIFICATION=true
ACCOUNT_MAIL_MODE=local
APP_PUBLIC_URL=http://localhost:8081/
```

Execute em terminais separados, na raiz:

```powershell
python tools/start_backend.py --manual-schema
python -m http.server 8081 --bind 127.0.0.1 --directory frontend
python tools/mailbox.py
```

Abra http://localhost:8081/ e consulte os e-mails em http://127.0.0.1:8025/. Copie o link completo da mensagem e abra no navegador. Nenhum e-mail real é enviado. As mensagens ficam em `%TEMP%/techedin-mailbox`, fora da pasta pública; contêm links sensíveis e devem ser removidas depois dos testes. O servidor da caixa aceita apenas conexões locais.

Para contas antigas, preencha o e-mail na tela de login e clique **Reenviar confirmação**. Não marcamos contas como verificadas sem o fluxo de confirmação. Solicitações repetidas têm intervalo de 60 segundos; apenas o último link da mesma finalidade permanece válido.

## SMTP em produção

Configure apenas no servidor Java (não no frontend/Vercel):

```dotenv
ACCOUNT_REQUIRE_VERIFICATION=true
ACCOUNT_MAIL_MODE=smtp
APP_PUBLIC_URL=https://techedin.vercel.app/
SMTP_HOST=servidor-do-provedor
SMTP_PORT=587
SMTP_USERNAME=usuario-do-provedor
SMTP_PASSWORD=segredo-no-servidor
SMTP_FROM=Techedin <contato@seu-dominio>
SMTP_AUTH=true
SMTP_STARTTLS=true
SESSION_COOKIE_SECURE=true
```

O remetente precisa estar validado no provedor, com DNS de autenticação conforme as instruções dele. Envio por SMTP não foi validado com um provedor real; o usuário escolheu caixa local nesta etapa. O modo local recusa destinos públicos: ele não deve ser usado após publicar.

Os links carregam tokens no fragmento da URL, removido pelo frontend após a leitura. Confirmação expira em 24 horas; redefinição em 30 minutos. O banco guarda somente SHA-256 dos tokens. Abrir um link não o consome: é necessário confirmar no formulário. Redefinir senha invalida todas as sessões anteriores; não confirma automaticamente o e-mail.

Exclusão exige senha atual e a palavra EXCLUIR. Remove conta, preferências, currículo, tokens e inscrições push por cascade. Dados públicos das vagas permanecem. Backups do banco e mensagens já enviadas seguem a retenção dos respectivos serviços; a exclusão não apaga esses backups. A aplicação não mantém o PDF/DOCX original.

## Publicação

Vercel deve publicar somente `frontend/`. A API Java e o coletor Python precisam de hospedagem própria. A API exige banco V7; SMTP permanece opcional e desativado por escolha do usuário. O coletor precisa permanecer ativo para alimentar alertas. Configure um proxy de `/api/**` na Vercel para a URL HTTPS real da API. Isso mantém as sessões no mesmo domínio do site; apontar diretamente para outro domínio exigiria rever cookies e CORS. Ainda não há URL de API publicada, portanto esse proxy não foi inventado nem ativado. Veja o passo a passo em [DEPLOYMENT.md](DEPLOYMENT.md).

Antes de abrir ao público: validar entrega SMTP e os fluxos completos por HTTPS, configurar limites também no proxy para múltiplas instâncias e substituir segredos que foram compartilhados anteriormente. O limitador atual é por processo e endereço direto; o proxy não deve tratar qualquer `X-Forwarded-For` fornecido pelo cliente como confiável.

O envio ocorre dentro de uma transação com timeout SMTP de cinco segundos por operação: falha ao cadastrar reverte o cadastro. Pode haver um e-mail com link inválido se o provedor aceitar o envio e o commit falhar; solicitar novo link resolve. Respostas de recuperação/reenvio não revelam se o e-mail existe, mas não prometemos tempo de resposta idêntico. Para escala maior, planejar fila/outbox e limpeza periódica de tokens expirados.
