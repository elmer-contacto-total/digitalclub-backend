package com.digitalgroup.holape.api.v1;

import com.digitalgroup.holape.integration.storage.S3StorageService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * File Controller
 * Handles file uploads and downloads
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final S3StorageService s3StorageService;

    /**
     * Upload file
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "uploads") String folder) {

        try {
            // Create folder path with client ID for organization
            String clientFolder = "clients/" + currentUser.getClientId() + "/" + folder;
            String key = s3StorageService.uploadFile(file, clientFolder);

            return ResponseEntity.ok(Map.of(
                    "result", "success",
                    "key", key,
                    "url", s3StorageService.getDownloadUrl(key),
                    "filename", file.getOriginalFilename(),
                    "size", file.getSize(),
                    "content_type", file.getContentType()
            ));

        } catch (Exception e) {
            log.error("Failed to upload file", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Get download URL for file
     */
    @GetMapping("/download")
    public ResponseEntity<Map<String, Object>> getDownloadUrl(
            @RequestParam String key) {

        try {
            if (!s3StorageService.fileExists(key)) {
                return ResponseEntity.notFound().build();
            }

            String url = s3StorageService.getDownloadUrl(key);

            return ResponseEntity.ok(Map.of(
                    "url", url
            ));

        } catch (Exception e) {
            log.error("Failed to get download URL", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Delete file
     */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> delete(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam String key) {

        try {
            // Verify user has access to this file (belongs to their client)
            if (!key.contains("/clients/" + currentUser.getClientId() + "/")) {
                return ResponseEntity.status(403).body(Map.of(
                        "result", "error",
                        "message", "Access denied"
                ));
            }

            s3StorageService.deleteFile(key);

            return ResponseEntity.ok(Map.of(
                    "result", "success"
            ));

        } catch (Exception e) {
            log.error("Failed to delete file", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Upload message attachment
     */
    @PostMapping("/message-attachment")
    public ResponseEntity<Map<String, Object>> uploadMessageAttachment(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long ticketId) {

        try {
            String folder = "clients/" + currentUser.getClientId() + "/messages";
            if (ticketId != null) {
                folder += "/ticket_" + ticketId;
            }

            String key = s3StorageService.uploadFile(file, folder);

            return ResponseEntity.ok(Map.of(
                    "result", "success",
                    "key", key,
                    "url", s3StorageService.getDownloadUrl(key),
                    "filename", file.getOriginalFilename(),
                    "size", file.getSize(),
                    "content_type", file.getContentType()
            ));

        } catch (Exception e) {
            log.error("Failed to upload message attachment", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Upload user profile image
     */
    @PostMapping("/profile-image")
    public ResponseEntity<Map<String, Object>> uploadProfileImage(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam("file") MultipartFile file) {

        try {
            // Validate file is an image
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                return ResponseEntity.badRequest().body(Map.of(
                        "result", "error",
                        "message", "File must be an image"
                ));
            }

            // Limit file size to 5MB
            if (file.getSize() > 5 * 1024 * 1024) {
                return ResponseEntity.badRequest().body(Map.of(
                        "result", "error",
                        "message", "File size must be less than 5MB"
                ));
            }

            String folder = "clients/" + currentUser.getClientId() + "/profiles";
            String key = s3StorageService.uploadFile(file, folder);

            return ResponseEntity.ok(Map.of(
                    "result", "success",
                    "key", key,
                    "url", s3StorageService.getDownloadUrl(key)
            ));

        } catch (Exception e) {
            log.error("Failed to upload profile image", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", e.getMessage()
            ));
        }
    }
}
