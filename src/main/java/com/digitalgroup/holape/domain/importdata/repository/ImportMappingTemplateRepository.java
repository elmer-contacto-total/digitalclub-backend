package com.digitalgroup.holape.domain.importdata.repository;

import com.digitalgroup.holape.domain.importdata.entity.ImportMappingTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImportMappingTemplateRepository extends JpaRepository<ImportMappingTemplate, Long> {

    List<ImportMappingTemplate> findByClientIdOrderByNameAsc(Long clientId);

    Optional<ImportMappingTemplate> findByClientIdAndName(Long clientId, String name);

    List<ImportMappingTemplate> findByClientIdAndIsFoh(Long clientId, Boolean isFoh);
}
