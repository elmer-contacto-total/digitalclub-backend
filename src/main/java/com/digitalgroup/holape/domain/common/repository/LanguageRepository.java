package com.digitalgroup.holape.domain.common.repository;

import com.digitalgroup.holape.domain.common.entity.Language;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LanguageRepository extends JpaRepository<Language, Long> {

    Optional<Language> findByLanguageCode(String languageCode);

    Optional<Language> findByName(String name);

    boolean existsByLanguageCode(String languageCode);

    boolean existsByName(String name);
}
