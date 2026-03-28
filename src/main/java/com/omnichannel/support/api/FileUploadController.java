package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.UploadedFileDto;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.service.GoogleDriveStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/files")
@RequiredArgsConstructor
public class FileUploadController {

    private final GoogleDriveStorageService googleDriveStorageService;

    @PostMapping(path = "/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UploadedFileDto>> upload(
            @RequestParam("file") MultipartFile file, Authentication authentication) throws Exception {
        if (!googleDriveStorageService.isConfigured()) {
            throw new ValidationException("Google Drive storage is not configured");
        }
        GoogleDriveStorageService.StoredDriveFile stored = googleDriveStorageService.upload(
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.bin",
                file.getContentType(),
                file.getBytes(),
                "Uploaded by authenticated support user");
        return ResponseEntity.ok(ApiResponse.success(new UploadedFileDto(
                "GOOGLE_DRIVE",
                "drive://" + stored.fileId(),
                stored.fileName(),
                stored.mimeType())));
    }
}
