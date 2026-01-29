package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.common.entity.Language;
import com.digitalgroup.holape.domain.common.repository.LanguageRepository;
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
 * Language Admin Controller
 * Equivalent to Rails Admin::LanguagesController
 * CRUD for languages
 */
@Slf4j
@RestController
@RequestMapping("/app/languages")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class LanguageAdminController {

    private final LanguageRepository languageRepository;

    /**
     * List all languages
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> index(
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        Page<Language> languagesPage = languageRepository.findAll(pageable);

        List<Map<String, Object>> data = languagesPage.getContent().stream()
                .map(this::mapLanguageToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "languages", data,
                "total", languagesPage.getTotalElements(),
                "page", page,
                "totalPages", languagesPage.getTotalPages()
        ));
    }

    /**
     * Get all languages (no pagination, for dropdowns)
     */
    @GetMapping("/all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<Map<String, Object>>> all() {
        List<Language> languages = languageRepository.findAll(Sort.by("name").ascending());

        List<Map<String, Object>> data = languages.stream()
                .map(l -> Map.<String, Object>of(
                        "id", l.getId(),
                        "name", l.getName(),
                        "code", l.getLanguageCode() != null ? l.getLanguageCode() : ""
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(data);
    }

    /**
     * Get single language
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        Language language = languageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Language", id));

        return ResponseEntity.ok(mapLanguageToResponse(language));
    }

    /**
     * Create language
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateLanguageRequest request) {
        Language language = new Language();
        language.setName(request.name());
        language.setLanguageCode(request.code());

        language = languageRepository.save(language);

        log.info("Created language: {}", language.getName());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "language", mapLanguageToResponse(language)
        ));
    }

    /**
     * Update language
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody UpdateLanguageRequest request) {

        Language language = languageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Language", id));

        if (request.name() != null) language.setName(request.name());
        if (request.code() != null) language.setLanguageCode(request.code());

        language = languageRepository.save(language);

        log.info("Updated language: {}", language.getName());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "language", mapLanguageToResponse(language)
        ));
    }

    /**
     * Delete language
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> destroy(@PathVariable Long id) {
        Language language = languageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Language", id));

        languageRepository.delete(language);

        log.info("Deleted language: {}", language.getName());

        return ResponseEntity.ok(Map.of("result", "success"));
    }

    private Map<String, Object> mapLanguageToResponse(Language language) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", language.getId());
        map.put("name", language.getName());
        map.put("code", language.getLanguageCode());
        map.put("created_at", language.getCreatedAt());
        map.put("updated_at", language.getUpdatedAt());
        return map;
    }

    public record CreateLanguageRequest(String name, String code) {}
    public record UpdateLanguageRequest(String name, String code) {}
}
