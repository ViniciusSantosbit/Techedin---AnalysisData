package com.techedin.backend.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Local delivery never writes secrets beneath the served project directory. */
@Service
public class AccountMail {
    private final JavaMailSender sender;
    private final String mode, base, from;
    private final Path mailbox;
    @org.springframework.beans.factory.annotation.Autowired
    public AccountMail(JavaMailSender sender,
        @Value("${ACCOUNT_MAIL_MODE:disabled}") String mode,
        @Value("${APP_PUBLIC_URL:http://localhost:8081/}") String base,
        @Value("${SMTP_FROM:}") String from) {
        this(sender,mode,base,from,Path.of(System.getProperty("java.io.tmpdir"), "techedin-mailbox"));
    }
    AccountMail(JavaMailSender sender,String mode,String base,String from,Path mailbox) {
        this.sender=sender; this.mode=mode; this.base=base; this.from=from; this.mailbox=mailbox;
    }
    public boolean local() { return mode.equals("local"); }
    public boolean enabled() { return mode.equals("local") || mode.equals("smtp"); }
    public void send(String email, String purpose, String token) {
        try {
            URI uri=URI.create(base);
            boolean loopback=java.util.Set.of("localhost","127.0.0.1","::1").contains(uri.getHost());
            if (uri.getFragment()!=null || uri.getQuery()!=null || uri.getUserInfo()!=null || uri.getHost()==null ||
                !(uri.getScheme().equals("https") || (loopback && uri.getScheme().equals("http")))) throw new IllegalStateException();
            String subject=purpose.equals("verification") ? "Confirme seu e-mail no Techedin" : "Redefina sua senha no Techedin";
            String link=base+"#"+(purpose.equals("verification") ? "verify-email=" : "reset-password=")+token;
            String body=subject+"\n\nAbra o link e confirme a solicitação:\n"+link+
                "\n\nEste link é temporário e funciona uma única vez. Se não solicitou, ignore esta mensagem.";
            if (local() && loopback) {
                Files.createDirectories(mailbox);
                Files.writeString(mailbox.resolve(UUID.randomUUID()+".eml"),
                    "To: "+email+"\nSubject: "+subject+"\n\n"+body,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            } else if (mode.equals("smtp") && !from.isBlank()) {
                var message=new SimpleMailMessage(); message.setFrom(from); message.setTo(email);
                message.setSubject(subject); message.setText(body); sender.send(message);
            } else throw new IllegalStateException();
        } catch (Exception error) {
            // Do not log message content, tokens, recipient or provider credentials.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Não foi possível enviar o e-mail. Tente novamente mais tarde.");
        }
    }
}
