package com.digitalgroup.holape.integration.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Primary;
import java.io.IOException;
import java.time.Duration;

/**
 * S3 implementation of MediaStorageService.
 * Adapts the existing S3 infrastructure for media capture storage.
 * Primary bean for MediaStorageService injection.
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "media.storage.type", havingValue = "s3", matchIfMissing = true)
public class S3MediaStorageAdapter implements MediaStorageService {

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

    @Value("${media.storage.folder:media-captures}")
    private String mediaFolder;

    private S3Client s3Client;
    private S3Presigner presigner;

    @PostConstruct
    public void initialize() {
        if (!enabled || accessKeyId.isEmpty() || secretAccessKey.isEmpty()) {
            log.info("S3 Media Storage is disabled or not configured");
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

            log.info("S3 Media Storage initialized for bucket: {}, folder: {}", bucketName, mediaFolder);

        } catch (Exception e) {
            log.error("Failed to initialize S3 Media Storage", e);
        }
    }

    @Override
    public String upload(byte[] data, String path, String contentType) {
        if (!isEnabled()) {
            throw new IllegalStateException("S3 Media Storage is not enabled");
        }

        String fullPath = mediaFolder + "/" + path;

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fullPath)
                    .contentType(contentType)
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(data));

            log.info("Uploaded media to S3: {}", fullPath);
            return fullPath;

        } catch (S3Exception e) {
            log.error("Failed to upload media to S3: {}", fullPath, e);
            throw new RuntimeException("Failed to upload media: " + e.getMessage());
        }
    }

    @Override
    public byte[] download(String path) {
        if (!isEnabled()) {
            throw new IllegalStateException("S3 Media Storage is not enabled");
        }

        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(path)
                    .build();

            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);
            return response.readAllBytes();

        } catch (S3Exception | IOException e) {
            log.error("Failed to download media from S3: {}", path, e);
            throw new RuntimeException("Failed to download media: " + e.getMessage());
        }
    }

    @Override
    public void delete(String path) {
        if (!isEnabled()) {
            return;
        }

        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(path)
                    .build();

            s3Client.deleteObject(request);
            log.info("Deleted media from S3: {}", path);

        } catch (S3Exception e) {
            log.error("Failed to delete media from S3: {}", path, e);
        }
    }

    @Override
    public String getPublicUrl(String path) {
        if (!isEnabled()) {
            throw new IllegalStateException("S3 Media Storage is not enabled");
        }

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(path)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofHours(1))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            return presignedRequest.url().toString();

        } catch (S3Exception e) {
            log.error("Failed to generate presigned URL for media: {}", path, e);
            throw new RuntimeException("Failed to generate download URL: " + e.getMessage());
        }
    }

    @Override
    public boolean exists(String path) {
        if (!isEnabled()) {
            return false;
        }

        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(path)
                    .build();

            s3Client.headObject(request);
            return true;

        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            log.error("Error checking media existence: {}", path, e);
            return false;
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled && s3Client != null;
    }
}
