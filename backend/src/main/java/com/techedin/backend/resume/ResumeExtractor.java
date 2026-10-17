package com.techedin.backend.resume;

import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.xml.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

@Component
public class ResumeExtractor {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_TEXT = 100000;
    private static final int MAX_EXPANDED = 8 * 1024 * 1024;
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String MAIN_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new SynchronousQueue<>(), task -> { var thread = new Thread(task, "resume-extractor"); thread.setDaemon(true); return thread; });
    public record Extracted(String fileName, String fileType, int fileSize, String text) {}

    public Extracted extract(MultipartFile file) {
        if (file.isEmpty()) throw invalid("Selecione um arquivo PDF ou DOCX.");
        if (file.getSize() > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "O currículo deve ter no máximo 5 MB.");
        Future<Extracted> future;
        try { future = worker.submit(() -> parse(file)); }
        catch (RejectedExecutionException exception) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Outro currículo está sendo processado. Tente novamente em instantes.");
        }
        try { return future.get(15, TimeUnit.SECONDS); }
        catch (TimeoutException exception) {
            future.cancel(true);
            throw invalid("O processamento demorou demais. Tente uma versão mais simples do currículo.");
        } catch (InterruptedException exception) {
            future.cancel(true); Thread.currentThread().interrupt();
            throw invalid("O processamento foi interrompido. Tente novamente.");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ResponseStatusException status) throw status;
            throw invalid("Não foi possível ler o arquivo. Verifique se é um PDF sem senha ou um DOCX válido.");
        }
    }

    private Extracted parse(MultipartFile file) throws IOException, XMLStreamException {
        String name = Normalizer.normalize(Objects.toString(file.getOriginalFilename(), "curriculo"), Normalizer.Form.NFC)
            .replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        String lower = name.toLowerCase(Locale.ROOT);
        boolean pdf = lower.endsWith(".pdf"), docx = lower.endsWith(".docx");
        if (!pdf && !docx) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Use um arquivo PDF ou DOCX.");
        if (name.codePointCount(0, name.length()) > 180) name = name.substring(0, name.offsetByCodePoints(0, 175)) + (pdf ? ".pdf" : ".docx");
        byte[] bytes;
        try (var input = file.getInputStream()) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "O currículo deve ter no máximo 5 MB.");
        String text;
        if (pdf && bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            text = pdfText(bytes);
        } else if (docx && bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4) {
            text = docxText(bytes);
        } else throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "O conteúdo do arquivo não corresponde ao formato PDF/DOCX.");
        return new Extracted(name, pdf ? "application/pdf" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            bytes.length, validateText(text));
    }

    private String pdfText(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw invalid("Envie um PDF sem proteção por senha.");
            if (document.getNumberOfPages() > 20) throw invalid("O currículo pode ter no máximo 20 páginas.");
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            var writer = new LimitedWriter();
            stripper.writeText(document, writer);
            return writer.toString();
        }
    }

    private String docxText(byte[] bytes) throws IOException, XMLStreamException {
        Map<String, byte[]> parts = new TreeMap<>();
        int expanded = 0, entries = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++entries > 256) throw invalid("O DOCX possui conteúdo demais. Envie uma versão simplificada.");
                byte[] content = zip.readNBytes(MAX_EXPANDED - expanded + 1);
                expanded += content.length;
                if (expanded > MAX_EXPANDED) throw invalid("O DOCX excede o limite de conteúdo descompactado.");
                String name = entry.getName();
                if (name.equals("[Content_Types].xml") || name.equals("word/document.xml") ||
                    name.matches("word/(header\\d+|footer\\d+|footnotes|endnotes)\\.xml")) {
                    if (parts.putIfAbsent(name, content) != null) throw invalid("O DOCX contém partes duplicadas.");
                }
            }
        }
        if (!parts.containsKey("word/document.xml") || !parts.containsKey("[Content_Types].xml")) throw invalid("O arquivo não é um DOCX válido.");
        boolean validType = false;
        var types = xml(parts.remove("[Content_Types].xml"));
        try {
            while (types.hasNext()) {
                int event = types.next();
                if (event == XMLStreamConstants.DTD) throw invalid("Declarações DTD não são permitidas no DOCX.");
                if (event == XMLStreamConstants.START_ELEMENT && types.getLocalName().equals("Override") &&
                    "/word/document.xml".equals(types.getAttributeValue(null, "PartName"))) {
                    validType = MAIN_TYPE.equals(types.getAttributeValue(null, "ContentType"));
                }
            }
        } finally { types.close(); }
        if (!validType) throw invalid("Envie um DOCX padrão, sem macros.");
        var output = new LimitedWriter();
        for (var part : parts.values()) {
            var reader = xml(part);
            boolean text = false;
            try {
                while (reader.hasNext()) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD) throw invalid("Declarações DTD não são permitidas no DOCX.");
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String local = reader.getLocalName(), namespace = reader.getNamespaceURI();
                        text = local.equals("t") && (WORD_NS.equals(namespace) || DRAWING_NS.equals(namespace));
                        if (WORD_NS.equals(namespace) && (local.equals("tab") || local.equals("br"))) output.write("\n");
                    } else if (event == XMLStreamConstants.CHARACTERS && text) output.write(reader.getText());
                    else if (event == XMLStreamConstants.END_ELEMENT) {
                        text = false;
                        if (WORD_NS.equals(reader.getNamespaceURI()) && Set.of("p", "tc").contains(reader.getLocalName())) output.write("\n");
                    }
                }
                output.write("\n");
            } finally { reader.close(); }
        }
        return output.toString();
    }

    private XMLStreamReader xml(byte[] bytes) throws XMLStreamException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("External resource denied"); });
        return factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }
    public static String validateText(String value) {
        String text = Normalizer.normalize(value, Normalizer.Form.NFC).replace("\r\n", "\n").replace('\r', '\n')
            .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
        if (text.codePointCount(0, text.length()) < 40) throw invalid("Não encontramos texto suficiente. Envie um currículo com texto selecionável; PDFs digitalizados precisam de OCR.");
        if (text.length() > MAX_TEXT) throw invalid("O texto do currículo pode ter no máximo 100.000 caracteres.");
        return text;
    }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
    private static class LimitedWriter extends Writer {
        private final StringBuilder content = new StringBuilder();
        @Override public void write(char[] value, int offset, int length) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
            if (content.length() + length > MAX_TEXT) throw invalid("O texto do currículo excede 100.000 caracteres.");
            content.append(value, offset, length);
        }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString() { return content.toString(); }
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
