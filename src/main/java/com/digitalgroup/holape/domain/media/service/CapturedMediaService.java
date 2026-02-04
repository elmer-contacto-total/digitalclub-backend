package com.digitalgroup.holape.domain.media.service;

import com.digitalgroup.holape.api.v1.dto.media.SaveCapturedMediaRequest;
import com.digitalgroup.holape.domain.media.entity.CapturedMedia;
import com.digitalgroup.holape.domain.media.enums.CapturedMediaType;
import com.digitalgroup.holape.domain.media.repository.CapturedMediaRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.integration.storage.MediaStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CapturedMediaService {

    private final CapturedMediaRepository mediaRepository;
    private final MediaStorageService storageService;
    private final UserRepository userRepository;

    /**
     * Save a captured media from Electron app.
     * Performs deduplication based on SHA-256 hash.
     *
     * @param request the save request with base64 encoded data
     * @return Optional containing the saved media, or empty if duplicate
     */
    @Transactional
    public Optional<CapturedMedia> saveMedia(SaveCapturedMediaRequest request) {
        try {
            // Decode base64
            String base64Data = request.getData();
            if (base64Data.contains(",")) {
                // Remove "data:image/jpeg;base64," prefix if present
                base64Data = base64Data.substring(base64Data.indexOf(",") + 1);
            }
            byte[] fileData = Base64.getDecoder().decode(base64Data);

            // Calculate SHA-256 hash
            String hash = calculateSha256(fileData);

            // Check for duplicates
            if (mediaRepository.existsBySha256Hash(hash)) {
                log.info("[CapturedMediaService] Duplicate media ignored: {}", hash.substring(0, 16));
                return Optional.empty();
            }

            // Parse media type
            CapturedMediaType mediaType;
            try {
                mediaType = CapturedMediaType.valueOf(request.getMediaType().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("[CapturedMediaService] Unknown media type: {}, defaulting to IMAGE", request.getMediaType());
                mediaType = CapturedMediaType.IMAGE;
            }

            // Generate storage path
            LocalDateTime now = LocalDateTime.now();
            String year = String.valueOf(now.getYear());
            String month = String.format("%02d", now.getMonthValue());
            String day = String.format("%02d", now.getDayOfMonth());
            String extension = getExtension(request.getMimeType());
            String storagePath = String.format("%s/%s/%s/%s/%s/%s.%s",
                    request.getUserId(),
                    mediaType.name().toLowerCase(),
                    year, month, day,
                    request.getMediaId(),
                    extension
            );

            // Upload to storage
            String filePath = null;
            String publicUrl = null;
            if (storageService.isEnabled()) {
                filePath = storageService.upload(fileData, storagePath, request.getMimeType());
                publicUrl = storageService.getPublicUrl(filePath);
            } else {
                log.warn("[CapturedMediaService] Storage not enabled, media will be saved without file");
                filePath = storagePath; // Store path even if not actually uploaded
            }

            // Parse capture timestamp
            LocalDateTime capturedAt;
            try {
                capturedAt = LocalDateTime.parse(request.getCapturedAt(), DateTimeFormatter.ISO_DATE_TIME);
            } catch (Exception e) {
                capturedAt = LocalDateTime.now();
            }

            // Look up agent (the logged-in user in Electron)
            User agent = null;
            if (request.getAgentId() != null) {
                agent = userRepository.findById(request.getAgentId()).orElse(null);
                if (agent == null) {
                    log.warn("[CapturedMediaService] Agent not found: {}", request.getAgentId());
                }
            }

            // Look up client user by chat phone
            User clientUser = null;
            if (request.getChatPhone() != null && !request.getChatPhone().isEmpty()) {
                clientUser = findUserByPhone(request.getChatPhone());
            }

            // Create entity
            CapturedMedia media = CapturedMedia.builder()
                    .mediaUuid(request.getMediaId())
                    .agent(agent)
                    .clientUser(clientUser)
                    .userFingerprint(request.getUserId())
                    .chatPhone(request.getChatPhone())
                    .chatName(request.getChatName())
                    .mediaType(mediaType)
                    .mimeType(request.getMimeType())
                    .filePath(filePath)
                    .publicUrl(publicUrl)
                    .sizeBytes(request.getSize())
                    .durationSeconds(request.getDuration())
                    .sha256Hash(hash)
                    .whatsappMessageId(request.getWhatsappMessageId())
                    .captureSource(request.getSource())
                    .capturedAt(capturedAt)
                    .build();

            CapturedMedia saved = mediaRepository.save(media);
            log.info("[CapturedMediaService] Media saved: {} ({} bytes) agent={} client={}",
                    request.getMediaId(), fileData.length,
                    agent != null ? agent.getId() : "null",
                    clientUser != null ? clientUser.getId() : "null");

            return Optional.of(saved);

        } catch (Exception e) {
            log.error("[CapturedMediaService] Error saving media: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    public Optional<CapturedMedia> findByUuid(String uuid) {
        return mediaRepository.findByMediaUuid(uuid);
    }

    public List<CapturedMedia> findByChatPhone(String phone, List<CapturedMediaType> types, int limit) {
        if (limit <= 50) {
            return mediaRepository.findTop50ByChatPhoneAndMediaTypeInOrderByCapturedAtDesc(phone, types);
        }
        return mediaRepository.findByChatPhoneAndMediaTypeInOrderByCapturedAtDesc(phone, types);
    }

    public Page<CapturedMedia> findByUserFingerprint(String fingerprint, Pageable pageable) {
        return mediaRepository.findByUserFingerprintOrderByCapturedAtDesc(fingerprint, pageable);
    }

    public long countTotal() {
        return mediaRepository.count();
    }

    public long countByType(CapturedMediaType type) {
        return mediaRepository.countByMediaType(type);
    }

    /**
     * Refresh the public URL for a media (URLs expire after 1 hour)
     */
    public String refreshPublicUrl(CapturedMedia media) {
        if (storageService.isEnabled() && media.getFilePath() != null) {
            String newUrl = storageService.getPublicUrl(media.getFilePath());
            media.setPublicUrl(newUrl);
            mediaRepository.save(media);
            return newUrl;
        }
        return media.getPublicUrl();
    }

    /**
     * Download media content
     */
    public byte[] downloadMedia(CapturedMedia media) {
        if (!storageService.isEnabled()) {
            throw new IllegalStateException("Storage service is not enabled");
        }
        return storageService.download(media.getFilePath());
    }

    private String calculateSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("Error calculating SHA-256", e);
            return "";
        }
    }

    private String getExtension(String mimeType) {
        if (mimeType == null) return "bin";
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "audio/ogg", "audio/ogg; codecs=opus" -> "ogg";
            case "audio/mpeg" -> "mp3";
            case "audio/mp4" -> "m4a";
            case "audio/webm" -> "webm";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
    }

    /**
     * Find user by phone number, trying multiple formats
     */
    private User findUserByPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return null;
        }

        // Normalize phone (remove non-digits)
        String normalizedPhone = phone.replaceAll("[^0-9]", "");

        // Try exact match first
        Optional<User> user = userRepository.findByPhone(normalizedPhone);
        if (user.isPresent()) {
            return user.get();
        }

        // Try without country code (last 9 digits for Peru)
        if (normalizedPhone.length() > 9) {
            String shortPhone = normalizedPhone.substring(normalizedPhone.length() - 9);
            user = userRepository.findByPhone(shortPhone);
            if (user.isPresent()) {
                return user.get();
            }

            // Try with Peru country code
            String withCountryCode = "51" + shortPhone;
            user = userRepository.findByPhone(withCountryCode);
            if (user.isPresent()) {
                return user.get();
            }
        }

        return null;
    }
}
