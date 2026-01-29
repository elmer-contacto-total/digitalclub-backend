package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.entity.ClientSetting;
import com.digitalgroup.holape.domain.client.entity.ClientStructure;
import com.digitalgroup.holape.domain.client.service.ClientService;
import com.digitalgroup.holape.domain.common.entity.Country;
import com.digitalgroup.holape.domain.common.enums.ClientType;
import com.digitalgroup.holape.domain.common.enums.DocType;
import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.common.repository.CountryRepository;
import com.digitalgroup.holape.domain.prospect.service.ProspectService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.digitalgroup.holape.web.dto.PagedResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Client Admin Controller
 * Equivalent to Rails Admin::ClientsController
 * Manages client (tenant) configuration
 *
 * Aligned with actual entity structure:
 * - Client has: name, companyName, docType, docNumber, status, clientType, etc.
 * - ClientSetting is name-value pairs (name, stringValue, integerValue, etc.)
 */
@Slf4j
@RestController
@RequestMapping("/app/clients")
@RequiredArgsConstructor
public class ClientAdminController {

    private final ClientService clientService;
    private final CountryRepository countryRepository;
    private final ProspectService prospectService;

    /**
     * List all clients (Super Admin only) with standard REST pagination
     */
    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<PagedResponse<Map<String, Object>>> index(
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int pageSize) {

        Pageable pageable = PageRequest.of(page - 1, pageSize, Sort.by("name").ascending());
        Page<Client> clientsPage = clientService.findAll(pageable);

        List<Map<String, Object>> data = clientsPage.getContent().stream()
                .map(this::mapClientToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.of(data, clientsPage.getTotalElements(), page, pageSize));
    }

    /**
     * Create a new client (Super Admin only)
     * PARIDAD RAILS: Admin::ClientsController#create
     */
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateClientRequest request) {
        // Build Client entity
        Client client = Client.builder()
                .name(request.name())
                .companyName(request.companyName())
                .docType(parseDocType(request.docType()))
                .docNumber(request.docNumber())
                .clientType(parseClientType(request.clientType()))
                .status(parseStatus(request.status()))
                .build();

        // Set default country (PARIDAD: Rails Country.first.id)
        Country defaultCountry = countryRepository.findAll(PageRequest.of(0, 1)).getContent()
                .stream().findFirst()
                .orElseThrow(() -> new RuntimeException("No countries found in database"));
        client.setCountry(defaultCountry);

        // Build ClientStructure if provided
        if (request.clientStructure() != null) {
            ClientStructure structure = buildClientStructure(request.clientStructure());
            structure.setClient(client);
            client.setClientStructure(structure);
        }

        Client savedClient = clientService.create(client);

        return ResponseEntity.ok(mapClientToResponse(savedClient));
    }

    /**
     * Get current client info
     */
    @GetMapping("/current")
    public ResponseEntity<Map<String, Object>> current(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        Client client = clientService.findById(currentUser.getClientId());
        return ResponseEntity.ok(mapClientToResponse(client));
    }

    /**
     * Get client by ID
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or @clientService.isUserClient(#id, principal.clientId)")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        Client client = clientService.findById(id);
        return ResponseEntity.ok(mapClientToResponse(client));
    }

    /**
     * Update client
     * PARIDAD RAILS: Admin::ClientsController#update with all fields
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or (hasRole('ADMIN') and @clientService.isUserClient(#id, principal.clientId))")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody UpdateClientRequest request) {

        Client client = clientService.findById(id);

        // Update basic fields
        if (request.name() != null) {
            client.setName(request.name());
        }
        if (request.companyName() != null) {
            client.setCompanyName(request.companyName());
        }
        if (request.docType() != null) {
            client.setDocType(parseDocType(request.docType()));
        }
        if (request.docNumber() != null) {
            client.setDocNumber(request.docNumber());
        }
        if (request.clientType() != null) {
            client.setClientType(parseClientType(request.clientType()));
        }

        // Handle status from string or boolean
        if (request.status() != null) {
            client.setStatus(parseStatus(request.status()));
        } else if (request.active() != null) {
            client.setStatus(request.active() ? Status.ACTIVE : Status.INACTIVE);
        }

        // Update ClientStructure if provided
        if (request.clientStructure() != null) {
            ClientStructure structure = client.getClientStructure();
            if (structure == null) {
                structure = new ClientStructure();
                structure.setClient(client);
                client.setClientStructure(structure);
            }
            updateClientStructure(structure, request.clientStructure());
        }

        Client updatedClient = clientService.updateClientFull(client);

        return ResponseEntity.ok(mapClientToResponse(updatedClient));
    }

    /**
     * Get client settings
     */
    @GetMapping("/{id}/settings")
    @PreAuthorize("hasRole('SUPER_ADMIN') or @clientService.isUserClient(#id, principal.clientId)")
    public ResponseEntity<Map<String, Object>> getSettings(@PathVariable Long id) {
        List<ClientSetting> settings = clientService.getSettings(id);
        return ResponseEntity.ok(mapSettingsToResponse(settings));
    }

    /**
     * Update client settings
     */
    @PutMapping("/{id}/settings")
    @PreAuthorize("hasRole('SUPER_ADMIN') or (hasRole('ADMIN') and @clientService.isUserClient(#id, principal.clientId))")
    public ResponseEntity<Map<String, Object>> updateSettings(
            @PathVariable Long id,
            @RequestBody UpdateSettingsRequest request) {

        clientService.updateSettings(id, request.settings());
        List<ClientSetting> settings = clientService.getSettings(id);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "settings", mapSettingsToResponse(settings)
        ));
    }

    /**
     * Get client WhatsApp configuration
     */
    @GetMapping("/{id}/whatsapp")
    @PreAuthorize("hasRole('SUPER_ADMIN') or @clientService.isUserClient(#id, principal.clientId)")
    public ResponseEntity<Map<String, Object>> getWhatsAppConfig(@PathVariable Long id) {
        Map<String, Object> whatsappConfig = new HashMap<>();

        String phoneNumberId = clientService.getClientSettingValue(id, "whatsapp_phone_number_id");
        String businessAccountId = clientService.getClientSettingValue(id, "whatsapp_business_account_id");
        String accessToken = clientService.getClientSettingValue(id, "whatsapp_access_token");
        String webhookVerifyToken = clientService.getClientSettingValue(id, "whatsapp_webhook_verify_token");

        whatsappConfig.put("whatsapp_phone_number_id", phoneNumberId);
        whatsappConfig.put("whatsapp_business_account_id", businessAccountId);
        whatsappConfig.put("whatsapp_access_token_configured", accessToken != null && !accessToken.isEmpty());
        whatsappConfig.put("whatsapp_webhook_verify_token", webhookVerifyToken);

        return ResponseEntity.ok(whatsappConfig);
    }

    /**
     * Update client WhatsApp configuration
     */
    @PutMapping("/{id}/whatsapp")
    @PreAuthorize("hasRole('SUPER_ADMIN') or (hasRole('ADMIN') and @clientService.isUserClient(#id, principal.clientId))")
    public ResponseEntity<Map<String, Object>> updateWhatsAppConfig(
            @PathVariable Long id,
            @RequestBody UpdateWhatsAppConfigRequest request) {

        clientService.updateWhatsAppConfig(
                id,
                request.whatsappPhoneNumberId(),
                request.whatsappBusinessAccountId(),
                request.whatsappAccessToken(),
                request.whatsappWebhookVerifyToken()
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", "WhatsApp configuration updated"
        ));
    }

    /**
     * Delete client (Super Admin only)
     * PARIDAD RAILS: Admin::ClientsController#destroy
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        clientService.deleteClient(id);
        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", "Organización eliminada correctamente"
        ));
    }

    /**
     * Delete all prospects for a client (Super Admin only)
     * PARIDAD RAILS: Admin::ClientsController#destroy_prospects
     */
    @DeleteMapping("/{id}/prospects")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> deleteProspects(@PathVariable Long id) {
        int deletedCount = prospectService.deleteAllByClientId(id);
        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", String.format("Se eliminaron %d prospectos para la organización", deletedCount)
        ));
    }

    // ========== Helper methods ==========

    private DocType parseDocType(String docType) {
        if (docType == null || docType.isBlank()) return DocType.RUC;
        return switch (docType.toLowerCase()) {
            case "ruc" -> DocType.RUC;
            case "dni" -> DocType.DNI;
            case "passport" -> DocType.PASSPORT;
            default -> DocType.RUC;
        };
    }

    private ClientType parseClientType(String clientType) {
        if (clientType == null || clientType.isBlank()) return ClientType.WHATSAPP_APP;
        return switch (clientType.toLowerCase()) {
            case "whatsapp_app" -> ClientType.WHATSAPP_APP;
            case "whatsapp_business" -> ClientType.WHATSAPP_BUSINESS;
            case "point_to_point_only" -> ClientType.POINT_TO_POINT_ONLY;
            default -> ClientType.WHATSAPP_APP;
        };
    }

    private Status parseStatus(String status) {
        if (status == null || status.isBlank()) return Status.ACTIVE;
        return switch (status.toLowerCase()) {
            case "active" -> Status.ACTIVE;
            case "inactive" -> Status.INACTIVE;
            default -> Status.ACTIVE;
        };
    }

    private ClientStructure buildClientStructure(ClientStructureRequest req) {
        return ClientStructure.builder()
                .existsManagerLevel1(req.existsManagerLevel1() != null ? req.existsManagerLevel1() : false)
                .managerLevel1(req.managerLevel1())
                .existsManagerLevel2(req.existsManagerLevel2() != null ? req.existsManagerLevel2() : false)
                .managerLevel2(req.managerLevel2())
                .existsManagerLevel3(req.existsManagerLevel3() != null ? req.existsManagerLevel3() : false)
                .managerLevel3(req.managerLevel3())
                .existsManagerLevel4(req.existsManagerLevel4() != null ? req.existsManagerLevel4() : true)
                .managerLevel4(req.managerLevel4() != null ? req.managerLevel4() : "Supervisor")
                .existsAgent(req.existsAgent() != null ? req.existsAgent() : true)
                .agent(req.agent() != null ? req.agent() : "Sectorista")
                .build();
    }

    private void updateClientStructure(ClientStructure structure, ClientStructureRequest req) {
        if (req.existsManagerLevel1() != null) structure.setExistsManagerLevel1(req.existsManagerLevel1());
        if (req.managerLevel1() != null) structure.setManagerLevel1(req.managerLevel1());
        if (req.existsManagerLevel2() != null) structure.setExistsManagerLevel2(req.existsManagerLevel2());
        if (req.managerLevel2() != null) structure.setManagerLevel2(req.managerLevel2());
        if (req.existsManagerLevel3() != null) structure.setExistsManagerLevel3(req.existsManagerLevel3());
        if (req.managerLevel3() != null) structure.setManagerLevel3(req.managerLevel3());
        if (req.existsManagerLevel4() != null) structure.setExistsManagerLevel4(req.existsManagerLevel4());
        if (req.managerLevel4() != null) structure.setManagerLevel4(req.managerLevel4());
        if (req.existsAgent() != null) structure.setExistsAgent(req.existsAgent());
        if (req.agent() != null) structure.setAgent(req.agent());
    }

    private Map<String, Object> mapClientToResponse(Client client) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", client.getId());
        map.put("name", client.getName());
        map.put("companyName", client.getCompanyName());
        map.put("docType", client.getDocType() != null ? client.getDocType().name().toLowerCase() : null);
        map.put("docNumber", client.getDocNumber());
        map.put("clientType", client.getClientType() != null ? client.getClientType().name().toLowerCase() : null);
        map.put("status", client.getStatus() != null ? client.getStatus().ordinal() : 0);
        map.put("active", client.isActive());
        map.put("domainUrl", client.getDomainUrl());
        map.put("logoUrl", client.getLogoUrl());
        map.put("createdAt", client.getCreatedAt());
        map.put("updatedAt", client.getUpdatedAt());

        // WhatsApp info from client entity
        map.put("whatsappNumber", client.getWhatsappNumber());
        map.put("whatsappVerifiedName", client.getWhatsappVerifiedName());

        // Client Structure
        if (client.getClientStructure() != null) {
            map.put("clientStructure", mapClientStructureToResponse(client.getClientStructure()));
        }

        return map;
    }

    private Map<String, Object> mapClientStructureToResponse(ClientStructure structure) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", structure.getId());
        map.put("existsAdminLevel0", true);
        map.put("adminLevel0", "Administrador");
        map.put("existsManagerLevel1", structure.getExistsManagerLevel1());
        map.put("managerLevel1", structure.getManagerLevel1());
        map.put("existsManagerLevel2", structure.getExistsManagerLevel2());
        map.put("managerLevel2", structure.getManagerLevel2());
        map.put("existsManagerLevel3", structure.getExistsManagerLevel3());
        map.put("managerLevel3", structure.getManagerLevel3());
        map.put("existsManagerLevel4", structure.getExistsManagerLevel4());
        map.put("managerLevel4", structure.getManagerLevel4());
        map.put("existsAgent", structure.getExistsAgent());
        map.put("agent", structure.getAgent());
        map.put("existsClientLevel6", true);
        map.put("clientLevel6", "Cliente Final");
        return map;
    }

    private Map<String, Object> mapSettingsToResponse(List<ClientSetting> settings) {
        Map<String, Object> map = new HashMap<>();
        if (settings == null || settings.isEmpty()) return map;

        for (ClientSetting setting : settings) {
            map.put(setting.getName(), setting.getValue());
        }

        return map;
    }

    /**
     * Request DTO for creating a client
     * PARIDAD RAILS: client_params
     */
    public record CreateClientRequest(
            String name,
            String companyName,
            String docType,
            String docNumber,
            String clientType,
            String status,
            ClientStructureRequest clientStructure
    ) {}

    /**
     * Request DTO for updating a client
     * PARIDAD RAILS: client_params with all fields
     */
    public record UpdateClientRequest(
            String name,
            String companyName,
            String docType,
            String docNumber,
            String clientType,
            String status,
            Boolean active,
            ClientStructureRequest clientStructure
    ) {}

    /**
     * Request DTO for client structure (nested attributes)
     * PARIDAD RAILS: client_structure_attributes
     */
    public record ClientStructureRequest(
            Boolean existsAdminLevel0,
            String adminLevel0,
            Boolean existsManagerLevel1,
            String managerLevel1,
            Boolean existsManagerLevel2,
            String managerLevel2,
            Boolean existsManagerLevel3,
            String managerLevel3,
            Boolean existsManagerLevel4,
            String managerLevel4,
            Boolean existsAgent,
            String agent,
            Boolean existsClientLevel6,
            String clientLevel6
    ) {}

    public record UpdateSettingsRequest(Map<String, Object> settings) {}

    public record UpdateWhatsAppConfigRequest(
            String whatsappPhoneNumberId,
            String whatsappBusinessAccountId,
            String whatsappAccessToken,
            String whatsappWebhookVerifyToken
    ) {}
}
