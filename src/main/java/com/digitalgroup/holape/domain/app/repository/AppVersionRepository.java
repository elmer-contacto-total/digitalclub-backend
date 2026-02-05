package com.digitalgroup.holape.domain.app.repository;

import com.digitalgroup.holape.domain.app.entity.AppVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for AppVersion entity.
 */
@Repository
public interface AppVersionRepository extends JpaRepository<AppVersion, Long> {

    /**
     * Find the latest active version for a given platform.
     * Orders by publishedAt descending and returns the first result.
     */
    Optional<AppVersion> findFirstByPlatformAndActiveOrderByPublishedAtDesc(String platform, Boolean active);

    /**
     * Find a specific version by version string and platform.
     */
    Optional<AppVersion> findByVersionAndPlatform(String version, String platform);

    /**
     * Find the latest active version for a platform using explicit query.
     */
    @Query("SELECT v FROM AppVersion v WHERE v.platform = :platform AND v.active = true ORDER BY v.publishedAt DESC LIMIT 1")
    Optional<AppVersion> findLatestActiveByPlatform(@Param("platform") String platform);
}
