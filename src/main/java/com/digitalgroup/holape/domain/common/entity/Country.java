package com.digitalgroup.holape.domain.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "countries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Country {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "iso_code", nullable = false)
    private String isoCode;

    @Column(name = "flag_url")
    private String flagUrl;

    @Column(name = "default_locale", nullable = false)
    private String defaultLocale;

    @Column(name = "default_phone_country_code", nullable = false)
    private String defaultPhoneCountryCode;

    @Column(name = "default_currency", nullable = false)
    private String defaultCurrency;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
