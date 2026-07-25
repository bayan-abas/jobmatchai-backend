package com.jobmatchai.backend.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CVTextExtractorServiceTest {

    private final CVTextExtractorService cvTextExtractorService = new CVTextExtractorService();

    @Test
    void detectContentType_genuinePdfMagicBytes_detectsApplicationPdf() throws IOException {
        byte[] pdfBytes = "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< >>\nendobj\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);

        String detected = cvTextExtractorService.detectContentType(new ByteArrayInputStream(pdfBytes));

        assertThat(detected).isEqualTo("application/pdf");
    }

    @Test
    void detectContentType_genuineDocxContainerStructure_detectsWordprocessingmlDocument() throws IOException {
        byte[] docxBytes = minimalDocxBytes();

        String detected = cvTextExtractorService.detectContentType(new ByteArrayInputStream(docxBytes));

        assertThat(detected).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void detectContentType_executableRenamedAsPdf_isNeverDetectedAsPdfOrDocx() throws IOException {

        byte[] exeBytes = new byte[64];
        exeBytes[0] = 'M';
        exeBytes[1] = 'Z';
        exeBytes[2] = (byte) 0x90;
        exeBytes[3] = 0x00;

        String detected = cvTextExtractorService.detectContentType(new ByteArrayInputStream(exeBytes));

        assertThat(detected).isNotEqualTo("application/pdf");
        assertThat(detected).isNotEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void detectContentType_plainTextRenamedAsDocx_isNeverDetectedAsDocx() throws IOException {
        byte[] textBytes = "This is just a plain text file, not a real document.".getBytes(StandardCharsets.UTF_8);

        String detected = cvTextExtractorService.detectContentType(new ByteArrayInputStream(textBytes));

        assertThat(detected).isNotEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(detected).isNotEqualTo("application/pdf");
    }

    private static byte[] minimalDocxBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" "
                    + "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("_rels/.rels"));
            zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" "
                    + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" "
                    + "Target=\"word/document.xml\"/>"
                    + "</Relationships>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body><w:p><w:r><w:t>Sample CV text for testing.</w:t></w:r></w:p></w:body>"
                    + "</w:document>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
