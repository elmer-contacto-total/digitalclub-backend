package com.digitalgroup.holape.integration.storage;

/**
 * Interface for media storage operations.
 * Allows switching between S3 and Firebase storage implementations.
 */
public interface MediaStorageService {

    /**
     * Upload data to storage
     * @param data byte array of the file content
     * @param path storage path (e.g., "userId/image/2026/01/30/uuid.jpg")
     * @param contentType MIME type (e.g., "image/jpeg")
     * @return the storage key/path of the uploaded file
     */
    String upload(byte[] data, String path, String contentType);

    /**
     * Download file content from storage
     * @param path storage path
     * @return byte array of file content
     */
    byte[] download(String path);

    /**
     * Delete file from storage
     * @param path storage path
     */
    void delete(String path);

    /**
     * Get a permanent public URL for the file (no expiration)
     * @param path storage path
     * @return publicly accessible URL
     */
    String getPublicUrl(String path);

    /**
     * Check if file exists in storage
     * @param path storage path
     * @return true if file exists
     */
    boolean exists(String path);

    /**
     * Check if storage service is enabled
     * @return true if the service is properly configured and available
     */
    boolean isEnabled();
}
