package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.common.entity.Country;
import com.digitalgroup.holape.domain.common.repository.CountryRepository;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Country Admin Controller
 * Equivalent to Rails Admin::CountriesController
 * CRUD for countries
 *
 * Aligned with actual Country entity:
 * - isoCode (not code)
 * - defaultPhoneCountryCode (not phoneCode)
 * - defaultLocale, defaultCurrency, flagUrl
 */
@Slf4j
@RestController
@RequestMapping("/app/countries")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class CountryAdminController {

    private final CountryRepository countryRepository;

    /**
     * List all countries
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> index(
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        Page<Country> countriesPage = countryRepository.findAll(pageable);

        List<Map<String, Object>> data = countriesPage.getContent().stream()
                .map(this::mapCountryToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "countries", data,
                "total", countriesPage.getTotalElements(),
                "page", page,
                "totalPages", countriesPage.getTotalPages()
        ));
    }

    /**
     * Get all countries (no pagination, for dropdowns)
     */
    @GetMapping("/all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<Map<String, Object>>> all() {
        List<Country> countries = countryRepository.findAll(Sort.by("name").ascending());

        List<Map<String, Object>> data = countries.stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "name", c.getName(),
                        "code", c.getIsoCode() != null ? c.getIsoCode() : "",
                        "phone_code", c.getDefaultPhoneCountryCode() != null ? c.getDefaultPhoneCountryCode() : ""
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(data);
    }

    /**
     * Get single country
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        Country country = countryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Country", id));

        return ResponseEntity.ok(mapCountryToResponse(country));
    }

    /**
     * Create country
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateCountryRequest request) {
        Country country = new Country();
        country.setName(request.name());
        country.setIsoCode(request.isoCode());
        country.setDefaultPhoneCountryCode(request.phoneCode());
        country.setDefaultLocale(request.defaultLocale() != null ? request.defaultLocale() : "es");
        country.setDefaultCurrency(request.defaultCurrency() != null ? request.defaultCurrency() : "USD");

        country = countryRepository.save(country);

        log.info("Created country: {}", country.getName());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "country", mapCountryToResponse(country)
        ));
    }

    /**
     * Update country
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody UpdateCountryRequest request) {

        Country country = countryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Country", id));

        if (request.name() != null) country.setName(request.name());
        if (request.isoCode() != null) country.setIsoCode(request.isoCode());
        if (request.phoneCode() != null) country.setDefaultPhoneCountryCode(request.phoneCode());
        if (request.defaultLocale() != null) country.setDefaultLocale(request.defaultLocale());
        if (request.defaultCurrency() != null) country.setDefaultCurrency(request.defaultCurrency());

        country = countryRepository.save(country);

        log.info("Updated country: {}", country.getName());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "country", mapCountryToResponse(country)
        ));
    }

    /**
     * Delete country
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> destroy(@PathVariable Long id) {
        Country country = countryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Country", id));

        countryRepository.delete(country);

        log.info("Deleted country: {}", country.getName());

        return ResponseEntity.ok(Map.of("result", "success"));
    }

    private Map<String, Object> mapCountryToResponse(Country country) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", country.getId());
        map.put("name", country.getName());
        map.put("iso_code", country.getIsoCode());
        map.put("phone_code", country.getDefaultPhoneCountryCode());
        map.put("default_locale", country.getDefaultLocale());
        map.put("default_currency", country.getDefaultCurrency());
        map.put("flag_url", country.getFlagUrl());
        map.put("created_at", country.getCreatedAt());
        map.put("updated_at", country.getUpdatedAt());
        return map;
    }

    public record CreateCountryRequest(
            String name,
            String isoCode,
            String phoneCode,
            String defaultLocale,
            String defaultCurrency
    ) {}

    public record UpdateCountryRequest(
            String name,
            String isoCode,
            String phoneCode,
            String defaultLocale,
            String defaultCurrency
    ) {}
}
