package com.techedin.backend.resume;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class ResumeService {
    public record ResumeView(UUID revision, String fileName, String fileType, int fileSize, String text,
        OffsetDateTime uploadedAt, OffsetDateTime updatedAt) {}
    private final JdbcTemplate jdbc;
    public ResumeService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public ResumeView get(UUID user) {
        return jdbc.query("SELECT * FROM app_user_resume WHERE user_id = ?", (rs, row) -> new ResumeView(
            rs.getObject("revision", UUID.class), rs.getString("file_name"), rs.getString("file_type"), rs.getInt("file_size"),
            rs.getString("extracted_text"), rs.getObject("uploaded_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)), user).stream().findFirst().orElse(null);
    }
    @Transactional
    public ResumeView store(UUID user, UUID expectedRevision, ResumeExtractor.Extracted resume) {
        UUID revision = UUID.randomUUID();
        if (expectedRevision != null) {
            int updated = jdbc.update("""
            UPDATE app_user_resume SET revision = ?, file_name = ?, file_type = ?, file_size = ?, extracted_text = ?,
                uploaded_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revision = ?
            """, revision, resume.fileName(), resume.fileType(), resume.fileSize(), resume.text(), user, expectedRevision);
            if (updated == 0) throw conflict();
        } else {
            try {
                jdbc.update("""
                    INSERT INTO app_user_resume(user_id, revision, file_name, file_type, file_size, extracted_text)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, user, revision, resume.fileName(), resume.fileType(), resume.fileSize(), resume.text());
            } catch (DuplicateKeyException exception) { throw conflict(); }
        }
        return get(user);
    }
    @Transactional
    public ResumeView revise(UUID user, UUID revision, String value) {
        String text = ResumeExtractor.validateText(value);
        int updated = jdbc.update("""
            UPDATE app_user_resume SET extracted_text = ?, revision = ?, updated_at = CURRENT_TIMESTAMP
            WHERE user_id = ? AND revision = ?
            """, text, UUID.randomUUID(), user, revision);
        if (updated == 0) throw conflict();
        return get(user);
    }
    @Transactional
    public void delete(UUID user, UUID revision) {
        int removed = jdbc.update("DELETE FROM app_user_resume WHERE user_id = ? AND revision = ?", user, revision);
        if (removed == 0 && get(user) != null) throw conflict();
    }
    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "O currículo foi alterado em outra aba. Reabra seu perfil antes de continuar.");
    }
}
