package com.digitalgroup.holape.domain.media.repository;

import com.digitalgroup.holape.domain.media.entity.CapturedMedia;
import com.digitalgroup.holape.domain.media.enums.CapturedMediaType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CapturedMediaRepository extends JpaRepository<CapturedMedia, Long> {

    Optional<CapturedMedia> findByMediaUuid(String mediaUuid);

    Optional<CapturedMedia> findByWhatsappMessageId(String whatsappMessageId);

    List<CapturedMedia> findAllByWhatsappMessageId(String whatsappMessageId);

    List<CapturedMedia> findByChatPhoneAndMediaTypeInOrderByCapturedAtDesc(
            String chatPhone, List<CapturedMediaType> types);

    Page<CapturedMedia> findByUserFingerprintOrderByCapturedAtDesc(
            String userFingerprint, Pageable pageable);

    List<CapturedMedia> findByCapturedAtBetween(LocalDateTime from, LocalDateTime to);

    boolean existsBySha256Hash(String sha256Hash);

    boolean existsBySha256HashAndWhatsappMessageId(String sha256Hash, String whatsappMessageId);

    /**
     * Find first media with same SHA-256 hash (for reusing S3 file path on duplicate content)
     */
    Optional<CapturedMedia> findFirstBySha256Hash(String sha256Hash);

    long countByUserFingerprint(String userFingerprint);

    long countByMediaType(CapturedMediaType mediaType);

    List<CapturedMedia> findTop50ByChatPhoneAndMediaTypeInOrderByCapturedAtDesc(
            String chatPhone, List<CapturedMediaType> types);

    /**
     * Find captured media by client user ID
     */
    List<CapturedMedia> findByClientUserIdOrderByMessageSentAtDesc(Long clientUserId);

    /**
     * Find captured media by client user ID with limit
     */
    List<CapturedMedia> findTop100ByClientUserIdOrderByMessageSentAtDesc(Long clientUserId);

    /**
     * Find captured media by client user ID and media type
     */
    List<CapturedMedia> findByClientUserIdAndMediaTypeOrderByMessageSentAtDesc(
            Long clientUserId, CapturedMediaType mediaType);

    /**
     * Find captured media by client user ID ordered by message sent time (with fallback to captured time)
     * Uses COALESCE to handle null messageSentAt values
     */
    @Query("SELECT m FROM CapturedMedia m WHERE m.clientUser.id = :clientUserId " +
           "ORDER BY COALESCE(m.messageSentAt, m.capturedAt) DESC")
    List<CapturedMedia> findByClientUserIdOrderedByEffectiveTime(@Param("clientUserId") Long clientUserId, Pageable pageable);
}
