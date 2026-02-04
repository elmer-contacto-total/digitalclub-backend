package com.digitalgroup.holape.domain.media.repository;

import com.digitalgroup.holape.domain.media.entity.CapturedMedia;
import com.digitalgroup.holape.domain.media.enums.CapturedMediaType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CapturedMediaRepository extends JpaRepository<CapturedMedia, Long> {

    Optional<CapturedMedia> findByMediaUuid(String mediaUuid);

    List<CapturedMedia> findByChatPhoneAndMediaTypeInOrderByCapturedAtDesc(
            String chatPhone, List<CapturedMediaType> types);

    Page<CapturedMedia> findByUserFingerprintOrderByCapturedAtDesc(
            String userFingerprint, Pageable pageable);

    List<CapturedMedia> findByCapturedAtBetween(LocalDateTime from, LocalDateTime to);

    boolean existsBySha256Hash(String sha256Hash);

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
}
