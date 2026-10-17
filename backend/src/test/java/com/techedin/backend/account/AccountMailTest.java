package com.techedin.backend.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountMailTest {
    @TempDir Path directory;
    @Test void localPreviewUsesFragmentAndNeverSendsMail() throws Exception {
        var sender=mock(JavaMailSender.class);
        var mail=new AccountMail(sender,"local","http://localhost:8081/frontend/","",directory);
        mail.send("fixture@example.invalid","verification","A".repeat(43));
        try (var files=Files.list(directory)) {
            var paths=files.toList(); assertEquals(1,paths.size());
            String text=Files.readString(paths.getFirst());
            assertTrue(text.contains("http://localhost:8081/frontend/#verify-email="+"A".repeat(43)));
        }
        verifyNoInteractions(sender);
        assertThrows(ResponseStatusException.class,()->new AccountMail(sender,"local","https://techedin.vercel.app/","",directory).send("fixture@example.invalid","reset","A".repeat(43)));
    }
    @Test void smtpUsesConfiguredSenderAndDisabledModeFailsClosed() {
        var sender=mock(JavaMailSender.class);
        var mail=new AccountMail(sender,"smtp","https://techedin.vercel.app/","fixture@example.invalid",directory);
        mail.send("recipient@example.invalid","reset","A".repeat(43));
        var message=org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertEquals("fixture@example.invalid",message.getValue().getFrom());
        assertTrue(message.getValue().getText().contains("#reset-password="));
        assertThrows(ResponseStatusException.class,()->new AccountMail(sender,"disabled","https://techedin.vercel.app/","",directory).send("fixture@example.invalid","reset","A".repeat(43)));
    }
}
