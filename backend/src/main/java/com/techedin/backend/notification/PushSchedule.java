package com.techedin.backend.notification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="WEB_PUSH_ENABLED",havingValue="true")
public class PushSchedule {
    private final NotificationService service;
    public PushSchedule(NotificationService service) { this.service=service; }
    @Scheduled(initialDelay=60000,fixedDelay=60000) public void tick() {
        try { service.process(); } catch (Exception error) { org.slf4j.LoggerFactory.getLogger(PushSchedule.class).warn("Ciclo push não concluído; nova tentativa no próximo ciclo."); }
    }
}
