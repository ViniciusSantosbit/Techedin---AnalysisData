package com.techedin.backend.notification;

import com.techedin.backend.recommendation.RecommendationService;
import com.techedin.backend.resume.ResumeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class NotificationService {
    record Device(UUID id, UUID user, String endpoint, OffsetDateTime created, long cursor, Long pending, int attempts) {}
    private final JdbcTemplate jdbc;
    private final WebPushSender sender;
    private final RecommendationService recommendations;
    private final ResumeService resumes;
    public NotificationService(JdbcTemplate jdbc, WebPushSender sender, RecommendationService recommendations, ResumeService resumes) {
        this.jdbc=jdbc;this.sender=sender;this.recommendations=recommendations;this.resumes=resumes;
    }
    public Map<String,Object> config() {
        boolean enabled=sender.configured() && schemaReady();
        return Map.of("available",enabled,"publicKey",enabled ? sender.publicKey():"");
    }
    boolean schemaReady() {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE lower(table_schema)='public' AND lower(table_name)='app_push_subscription' AND lower(column_name)='cursor_job_id'",Integer.class)>0;
    }
    private void ready() { if (!sender.configured() || !schemaReady()) throw error(503,"Notificações ainda não configuradas no servidor."); }
    static String hash(String endpoint) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(endpoint.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Transactional
    public void subscribe(UUID user,String endpoint) {
        ready(); WebPushSender.endpoint(endpoint);
        if (resumes.get(user)==null) throw error(409,"Anexe seu currículo antes de ativar os alertas compatíveis.");
        jdbc.queryForObject("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE",UUID.class,user);
        var owners=jdbc.query("SELECT user_id FROM app_push_subscription WHERE endpoint_hash=?",(rs,row)->rs.getObject(1,UUID.class),hash(endpoint));
        if (!owners.isEmpty()) {
            if (!owners.getFirst().equals(user)) throw error(409,"Este dispositivo ainda está vinculado a outra conta. Desative a inscrição do navegador e tente novamente.");
            return;
        }
        if (jdbc.queryForObject("SELECT count(*) FROM app_push_subscription WHERE user_id=?",Integer.class,user)>=5) throw error(409,"Limite de cinco dispositivos por conta. Desative um dispositivo antes de adicionar outro.");
        Long baseline=jdbc.queryForObject("SELECT COALESCE(MAX(job_id),0) FROM dim_job",Long.class);
        jdbc.update("INSERT INTO app_push_subscription(subscription_id,user_id,endpoint_hash,endpoint,cursor_job_id) VALUES(?,?,?,?,?)",
            UUID.randomUUID(),user,hash(endpoint),endpoint,baseline);
    }
    public boolean subscribed(UUID user,String endpoint) {
        ready(); WebPushSender.endpoint(endpoint);
        return jdbc.queryForObject("SELECT count(*) FROM app_push_subscription WHERE user_id=? AND endpoint_hash=?",Integer.class,user,hash(endpoint))>0;
    }
    public void unsubscribe(UUID user,String endpoint) {
        if (!schemaReady()) return;
        jdbc.update("DELETE FROM app_push_subscription WHERE user_id=? AND endpoint_hash=?",user,hash(endpoint));
    }
    public void process() {
        if (!sender.configured() || !schemaReady()) return;
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
        var devices=jdbc.query("""
            SELECT * FROM app_push_subscription WHERE retry_after<=? AND (lease_until IS NULL OR lease_until<?)
            ORDER BY retry_after LIMIT 25
            """,(rs,row)->new Device(rs.getObject("subscription_id",UUID.class),rs.getObject("user_id",UUID.class),
            rs.getString("endpoint"),rs.getObject("created_at",OffsetDateTime.class),rs.getLong("cursor_job_id"),
            rs.getObject("pending_through",Long.class),rs.getInt("attempts")),now,now);
        for (var device:devices) {
            var claimTime=OffsetDateTime.now(ZoneOffset.UTC);
            if (jdbc.update("UPDATE app_push_subscription SET lease_until=? WHERE subscription_id=? AND retry_after<=? AND (lease_until IS NULL OR lease_until<?) AND cursor_job_id=? AND attempts=?",claimTime.plusMinutes(5),device.id(),claimTime,claimTime,device.cursor(),device.attempts())==0) continue;
            try { process(device,claimTime); }
            catch (Exception error) {
                // Never log endpoints, tokens, CVs or provider error bodies.
                retry(device,claimTime);
            }
        }
    }
    private void process(Device device,OffsetDateTime now) throws Exception {
        long through;
        if (device.pending()!=null) through=device.pending();
        else {
            var ids=jdbc.queryForList("SELECT job_id FROM dim_job WHERE job_id>? ORDER BY job_id LIMIT 100",Long.class,device.cursor());
            if (ids.isEmpty()) { release(device.id(),now); return; }
            through=ids.getLast();
        }
        OffsetDateTime since=device.created().isAfter(now.minusDays(1)) ? device.created():now.minusDays(1);
        // Re-check current CV/preferences on retries, so deletion or changed interests stop obsolete alerts.
        var matches=recommendations.matchesBetween(device.user(),device.cursor(),through,since);
        if (matches.stream().noneMatch(match -> match.relevance()>=40)) { finish(device.id(),through,now,false); return; }
        if (device.pending()==null) jdbc.update("UPDATE app_push_subscription SET pending_through=? WHERE subscription_id=?",through,device.id());
        // A recent successful delivery groups further matches for at least 15 minutes.
        var last=jdbc.queryForObject("SELECT last_sent_at FROM app_push_subscription WHERE subscription_id=?",OffsetDateTime.class,device.id());
        if (last!=null && last.isAfter(now.minusMinutes(15))) { release(device.id(),last.plusMinutes(15)); return; }
        int status=sender.send(device.endpoint());
        if (status==201 || status==202) finish(device.id(),through,now,true);
        else if (status==404 || status==410) jdbc.update("DELETE FROM app_push_subscription WHERE subscription_id=?",device.id());
        else retry(new Device(device.id(),device.user(),device.endpoint(),device.created(),device.cursor(),through,device.attempts()),now);
    }
    private void finish(UUID id,long through,OffsetDateTime now,boolean sent) {
        if (sent) jdbc.update("UPDATE app_push_subscription SET last_sent_at=? WHERE subscription_id=?",now,id);
        jdbc.update("UPDATE app_push_subscription SET cursor_job_id=?,pending_through=NULL,attempts=0,lease_until=NULL,retry_after=? WHERE subscription_id=?",through,now.plusMinutes(1),id);
    }
    private void release(UUID id,OffsetDateTime next) { jdbc.update("UPDATE app_push_subscription SET lease_until=NULL,retry_after=? WHERE subscription_id=?",next,id); }
    private void retry(Device device,OffsetDateTime now) {
        int attempts=device.attempts()+1;
        if (attempts>=3) {
            // Drop the failed batch rather than sending indefinitely; keep the device for future batches.
            Long pending=jdbc.queryForObject("SELECT pending_through FROM app_push_subscription WHERE subscription_id=?",Long.class,device.id());
            finish(device.id(),pending==null ? device.cursor():pending,now,false);
        } else jdbc.update("UPDATE app_push_subscription SET attempts=?,lease_until=NULL,retry_after=? WHERE subscription_id=?",attempts,now.plusMinutes(attempts*5L),device.id());
    }
    private static ResponseStatusException error(int status,String text) { return new ResponseStatusException(HttpStatus.valueOf(status),text); }
}
