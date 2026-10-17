package com.techedin.backend.resume;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ResumeExtractorTest {
    final ResumeExtractor extractor = new ResumeExtractor();
    @AfterEach void close() { extractor.close(); }
    MockMultipartFile file(String name, byte[] content) { return new MockMultipartFile("file", name, "application/octet-stream", content); }
    void rejects(int status, String name, byte[] content) {
        assertEquals(status, assertThrows(ResponseStatusException.class, () -> extractor.extract(file(name, content))).getStatusCode().value());
    }
    @Test void readsRealPdfAndSanitizesFileName() throws Exception {
        var result = extractor.extract(file("C:\\fakepath\\cv.PDF", ResumeFixtures.pdf(1, false, true)));
        assertEquals("cv.PDF", result.fileName());
        assertEquals("application/pdf", result.fileType());
        assertTrue(result.text().contains("Python"));
    }
    @Test void readsUnicodeDocxParagraphsAndTables() throws Exception {
        var result = extractor.extract(file("currículo.docx", ResumeFixtures.docx(
            ResumeFixtures.body("Formação e experiência em desenvolvimento. Java, Python e SQL; soluções de software."), false, false)));
        assertTrue(result.text().contains("Formação"));
        assertEquals("currículo.docx", result.fileName());
    }
    @Test void rejectsImagesEncryptedPdfAndExcessivePages() throws Exception {
        rejects(422, "scan.pdf", ResumeFixtures.pdf(1, false, false));
        rejects(422, "secret.pdf", ResumeFixtures.pdf(1, true, true));
        rejects(422, "pages.pdf", ResumeFixtures.pdf(21, false, true));
    }
    @Test void rejectsFalseExtensionsInvalidAndOversizedFiles() {
        rejects(415, "fake.pdf", "<html>Not a PDF</html>".getBytes());
        rejects(422, "broken.pdf", "%PDF-1.4 broken".getBytes());
        rejects(415, "curriculo.exe", new byte[] { 1, 2 });
        rejects(413, "large.pdf", new byte[ResumeExtractor.MAX_BYTES + 1]);
        rejects(422, "empty.pdf", new byte[0]);
    }
    @Test void rejectsDocxDtdMacrosAndZipBombs(@TempDir Path directory) throws Exception {
        Path sentinel = directory.resolve("external.txt");
        Files.writeString(sentinel, ResumeFixtures.TEXT);
        String entity = "<!DOCTYPE w:document [<!ENTITY unsafe SYSTEM '" + sentinel.toUri() + "'>]>" + ResumeFixtures.body("&unsafe;");
        rejects(422, "entity.docx", ResumeFixtures.docx(entity, false, false));
        rejects(422, "macro.docx", ResumeFixtures.docx(ResumeFixtures.body(ResumeFixtures.TEXT), true, false));
        rejects(422, "bomb.docx", ResumeFixtures.docx(ResumeFixtures.body(ResumeFixtures.TEXT), false, true));
        rejects(422, "text.docx", ResumeFixtures.docx(ResumeFixtures.body("x".repeat(100001)), false, false));
    }
}
