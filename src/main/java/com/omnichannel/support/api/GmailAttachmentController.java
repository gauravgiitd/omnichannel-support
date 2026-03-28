package com.omnichannel.support.api;

import com.omnichannel.support.service.GmailApiClient;
import java.nio.charset.StandardCharsets;
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
@RequestMapping("/v1/gmail")
@RequiredArgsConstructor
public class GmailAttachmentController {

    private final GmailApiClient gmailApiClient;

    @GetMapping("/messages/{messageId}/attachments/{attachmentId}/{filename}")
    public ResponseEntity<byte[]> downloadAttachment(
            @PathVariable("messageId") String messageId,
            @PathVariable("attachmentId") String attachmentId,
            @PathVariable("filename") String filename,
            Authentication authentication)
            throws Exception {
        byte[] bytes = gmailApiClient.getAttachment(messageId, attachmentId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }
}
