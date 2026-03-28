package com.omnichannel.support.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnichannel.support.domain.TicketDocument;
import com.omnichannel.support.security.TicketAccessService;
import com.omnichannel.support.service.DocumentService;
import com.omnichannel.support.service.GoogleDriveStorageService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/documents")
@RequiredArgsConstructor
public class DocumentContentController {

    private final DocumentService documentService;
    private final TicketAccessService ticketAccessService;
    private final GoogleDriveStorageService googleDriveStorageService;
    private final ObjectMapper objectMapper;

    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> content(
            @PathVariable("documentId") String documentId, Authentication authentication) throws Exception {
        TicketDocument document = documentService.getByPublicId(documentId);
        ticketAccessService.assertCanAccessTicket(authentication, document.getTicket().getTicketNumber());

        Map<String, Object> metadata = parseMetadata(document.getMetadataJson());
        String driveFileId = stringValue(metadata.get("drive_file_id"));
        if (driveFileId == null || driveFileId.isBlank()) {
            throw new com.omnichannel.support.error.NotFoundException("document content not available");
        }

        byte[] bytes = googleDriveStorageService.download(driveFileId);
        String fileName = stringValue(metadata.get("file_name"));
        String mimeType = stringValue(metadata.get("mime_type"));

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(fileName != null ? fileName : documentId, StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(mimeType != null && !mimeType.isBlank()
                        ? MediaType.parseMediaType(mimeType)
                        : MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
