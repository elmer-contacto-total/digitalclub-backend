package com.digitalgroup.holape.domain.common.repository;

import com.digitalgroup.holape.domain.common.entity.Country;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CountryRepository extends JpaRepository<Country, Long> {

    Optional<Country> findByIsoCode(String isoCode);

    Optional<Country> findByName(String name);

    Optional<Country> findByDefaultPhoneCountryCode(String phoneCode);

    boolean existsByIsoCode(String isoCode);

    boolean existsByName(String name);
}
