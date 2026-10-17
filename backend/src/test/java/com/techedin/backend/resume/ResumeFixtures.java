package com.techedin.backend.resume;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

final class ResumeFixtures {
    static final String TEXT = "Pessoa Exemplo - Desenvolvimento Java, Python e SQL. Experiencia com sistemas e testes de software.";
    static byte[] pdf(int pages, boolean encrypted, boolean text) throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                var page = new PDPage(); document.addPage(page);
                if (text) try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    stream.newLineAtOffset(30, 700); stream.showText(TEXT); stream.endText();
                }
            }
            if (encrypted) document.protect(new StandardProtectionPolicy("owner", "secret", new AccessPermission()));
            document.save(output); return output.toByteArray();
        }
    }
    static byte[] docx(String body, boolean macros, boolean bomb) throws IOException {
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
            String type = macros ? "application/vnd.ms-word.document.macroEnabled.main+xml"
                : "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
            part(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"" + type + "\"/></Types>");
            part(zip, "word/document.xml", body);
            if (bomb) { zip.putNextEntry(new ZipEntry("word/huge.bin")); zip.write(new byte[9 * 1024 * 1024]); zip.closeEntry(); }
            zip.finish(); return output.toByteArray();
        }
    }
    static String body(String text) {
        return "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>" + text + "</w:t></w:r></w:p></w:body></w:document>";
    }
    static void part(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
