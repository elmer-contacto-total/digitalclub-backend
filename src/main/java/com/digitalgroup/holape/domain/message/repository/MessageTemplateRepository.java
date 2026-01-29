package com.digitalgroup.holape.domain.message.repository;

import com.digitalgroup.holape.domain.common.enums.TemplateWhatsAppStatus;
import com.digitalgroup.holape.domain.message.entity.MessageTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {

    Page<MessageTemplate> findByClientId(Long clientId, Pageable pageable);

    Page<MessageTemplate> findByClientIdAndStatus(Long clientId, TemplateWhatsAppStatus status, Pageable pageable);

    Optional<MessageTemplate> findByNameAndClientId(String name, Long clientId);

    @Query("SELECT mt FROM MessageTemplate mt WHERE mt.client.id = :clientId AND mt.templateWhatsappStatus = com.digitalgroup.holape.domain.common.enums.TemplateWhatsAppStatus.APPROVED ORDER BY mt.name")
    List<MessageTemplate> findApprovedByClient(@Param("clientId") Long clientId);

    @Query("SELECT mt FROM MessageTemplate mt WHERE mt.client.id = :clientId AND mt.templateWhatsappStatus = com.digitalgroup.holape.domain.common.enums.TemplateWhatsAppStatus.APPROVED " +
           "AND LOWER(mt.name) LIKE LOWER(CONCAT('%', :search, '%'))")
    List<MessageTemplate> searchApproved(@Param("clientId") Long clientId, @Param("search") String search);

    long countByClientId(Long clientId);

    long countByClientIdAndStatus(Long clientId, TemplateWhatsAppStatus status);

    Page<MessageTemplate> findByClientIdAndTemplateWhatsappStatus(Long clientId, TemplateWhatsAppStatus status, Pageable pageable);

    List<MessageTemplate> findByClientIdAndTemplateWhatsappStatus(Long clientId, TemplateWhatsAppStatus status);

    /**
     * Find templates with active WhatsApp status
     * PARIDAD RAILS: scope :whatsapp_status_active (message_template.rb líneas 32-38)
     *
     * In Rails, active templates include: active_quality_pending, active_high_quality,
     * active_medium_quality, active_low_quality. In Spring Boot, these map to APPROVED status.
     *
     * Note: If additional WhatsApp quality statuses are needed, they should be added to
     * TemplateWhatsAppStatus enum and included in this query with IN clause.
     */
    @Query("SELECT mt FROM MessageTemplate mt WHERE mt.client.id = :clientId AND mt.templateWhatsappStatus = com.digitalgroup.holape.domain.common.enums.TemplateWhatsAppStatus.APPROVED ORDER BY mt.name")
    List<MessageTemplate> findWhatsappStatusActiveByClient(@Param("clientId") Long clientId);

    /**
     * Count templates with active WhatsApp status
     */
    @Query("SELECT COUNT(mt) FROM MessageTemplate mt WHERE mt.client.id = :clientId AND mt.templateWhatsappStatus = com.digitalgroup.holape.domain.common.enums.TemplateWhatsAppStatus.APPROVED")
    long countWhatsappStatusActiveByClient(@Param("clientId") Long clientId);
}
