package com.digitalgroup.holape.integration.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.UUID;

/**
 * S3 Storage Service
 * Handles file uploads/downloads to AWS S3
 * Implements MediaStorageService for captured media from Electron
 */
@Slf4j
@Service
public class S3StorageService implements MediaStorageService {

    @Value("${aws.access-key-id:}")
    private String accessKeyId;

    @Value("${aws.secret-access-key:}")
    private String secretAccessKey;

    @Value("${aws.region:us-east-1}")
    private String region;

    @Value("${aws.s3.bucket:}")
    private String bucketName;

    @Value("${aws.s3.enabled:false}")
    private boolean enabled;

    private S3Client s3Client;
    private S3Presigner presigner;

    @PostConstruct
    public void initialize() {
        if (!enabled || accessKeyId.isEmpty() || secretAccessKey.isEmpty()) {
            log.info("S3 Storage is disabled or not configured");
            return;
        }

        try {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

            s3Client = S3Client.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();

            presigner = S3Presigner.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();

            log.info("S3 Storage initialized for bucket: {}", bucketName);

        } catch (Exception e) {
            log.error("Failed to initialize S3 client", e);
        }
    }

    /**
     * Upload file to S3
     * @return The S3 key (path) of the uploaded file
     */
    public String uploadFile(MultipartFile file, String folder) throws IOException {
        if (!isEnabled()) {
            throw new IllegalStateException("S3 Storage is not enabled");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        String key = folder + "/" + UUID.randomUUID().toString() + extension;

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(file.getContentType())
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));

            log.info("Uploaded file to S3: {}", key);
            return key;

        } catch (S3Exception e) {
            log.error("Failed to upload file to S3", e);
            throw new IOException("Failed to upload file: " + e.getMessage());
        }
    }

    /**
     * Upload file from input stream
     */
    public String uploadFile(InputStream inputStream, String folder, String filename, String contentType, long contentLength) throws IOException {
        if (!isEnabled()) {
            throw new IllegalStateException("S3 Storage is not enabled");
        }

        String key = folder + "/" + UUID.randomUUID().toString() + "_" + filename;

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .build();

            s3Client.putObject(request, RequestBody.fromInputStream(inputStream, contentLength));

            log.info("Uploaded file to S3: {}", key);
            return key;

        } catch (S3Exception e) {
            log.error("Failed to upload file to S3", e);
            throw new IOException("Failed to upload file: " + e.getMessage());
        }
    }

    /**
     * Get a pre-signed URL for downloading a file
     */
    public URL getPresignedUrl(String key, Duration expiration) {
        if (!isEnabled()) {
            throw new IllegalStateException("S3 Storage is not enabled");
        }

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(expiration)
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            return presignedRequest.url();

        } catch (S3Exception e) {
            log.error("Failed to generate presigned URL", e);
            throw new RuntimeException("Failed to generate download URL: " + e.getMessage());
        }
    }

    /**
     * Get a pre-signed URL with default 1 hour expiration
     */
    public String getDownloadUrl(String key) {
        URL url = getPresignedUrl(key, Duration.ofHours(1));
        return url.toString();
    }

    /**
     * Delete file from S3
     */
    public void deleteFile(String key) {
        if (!isEnabled()) {
            return;
        }

        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3Client.deleteObject(request);
            log.info("Deleted file from S3: {}", key);

        } catch (S3Exception e) {
            log.error("Failed to delete file from S3: {}", key, e);
        }
    }

    /**
     * Check if file exists
     */
    public boolean fileExists(String key) {
        if (!isEnabled()) {
            return false;
        }

        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3Client.headObject(request);
            return true;

        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            log.error("Error checking file existence: {}", key, e);
            return false;
        }
    }

    /**
     * Copy file within S3
     */
    public String copyFile(String sourceKey, String destinationFolder) {
        if (!isEnabled()) {
            throw new IllegalStateException("S3 Storage is not enabled");
        }

        String filename = sourceKey.substring(sourceKey.lastIndexOf("/") + 1);
        String destinationKey = destinationFolder + "/" + filename;

        try {
            CopyObjectRequest request = CopyObjectRequest.builder()
                    .sourceBucket(bucketName)
                    .sourceKey(sourceKey)
                    .destinationBucket(bucketName)
                    .destinationKey(destinationKey)
                    .build();

            s3Client.copyObject(request);
            log.info("Copied file in S3 from {} to {}", sourceKey, destinationKey);
            return destinationKey;

        } catch (S3Exception e) {
            log.error("Failed to copy file in S3", e);
            throw new RuntimeException("Failed to copy file: " + e.getMessage());
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled && s3Client != null;
    }

    public String getBucketName() {
        return bucketName;
    }

    // ==================== MediaStorageService Interface Implementation ====================

    /**
     * Upload data to S3 (implements MediaStorageService)
     * Used by CapturedMediaService for media captured from Electron
     */
    @Override
    public String upload(byte[] data, String path, String contentType) {
        if (!isEnabled()) {
            log.warn("S3 Storage is not enabled, cannot upload media");
            return path; // Return path as-is when disabled
        }

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(path)
                    .contentType(contentType)
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(data));

            log.info("Uploaded media to S3: {} ({} bytes)", path, data.length);
            return path;

        } catch (S3Exception e) {
            log.error("Failed to upload media to S3: {}", path, e);
            throw new RuntimeException("Failed to upload media: " + e.getMessage(), e);
        }
    }

    /**
     * Download file content from S3 (implements MediaStorageService)
     */
    @Override
    public byte[] download(String path) {
        if (!isEnabled()) {
            throw new IllegalStateException("S3 Storage is not enabled");
        }

        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(path)
                    .build();

            return s3Client.getObjectAsBytes(request).asByteArray();

        } catch (S3Exception e) {
            log.error("Failed to download media from S3: {}", path, e);
            throw new RuntimeException("Failed to download media: " + e.getMessage(), e);
        }
    }

    /**
     * Delete file from S3 (implements MediaStorageService)
     */
    @Override
    public void delete(String path) {
        deleteFile(path);
    }

    /**
     * Get public/presigned URL (implements MediaStorageService)
     * Returns a URL valid for 1 hour
     */
    @Override
    public String getPublicUrl(String path) {
        if (!isEnabled()) {
            return null;
        }
        return getDownloadUrl(path);
    }

    /**
     * Check if file exists (implements MediaStorageService)
     */
    @Override
    public boolean exists(String path) {
        return fileExists(path);
    }
}
