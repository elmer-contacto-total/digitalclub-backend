package com.digitalgroup.holape.domain.prospect.service;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.prospect.entity.Prospect;
import com.digitalgroup.holape.domain.prospect.repository.ProspectRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.BusinessException;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.util.PhoneUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Prospect Service
 * Handles prospect/lead management
 * Aligned with Rails schema: prospects table has manager_id, name, phone, client_id, status, upgraded_to_user
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProspectService {

    private final ProspectRepository prospectRepository;
    private final UserRepository userRepository;
    private final ClientRepository clientRepository;

    public Prospect findById(Long id) {
        return prospectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Prospect", id));
    }

    public Page<Prospect> findByClient(Long clientId, Pageable pageable) {
        return prospectRepository.findByClientIdAndUpgradedToUserFalse(clientId, pageable);
    }

    public Page<Prospect> findByClientAndStatus(Long clientId, Status status, Pageable pageable) {
        return prospectRepository.findByClientIdAndStatusAndUpgradedToUserFalse(clientId, status, pageable);
    }

    public Page<Prospect> search(Long clientId, String searchTerm, Pageable pageable) {
        return prospectRepository.searchByClientId(clientId, searchTerm, pageable);
    }

    public Optional<Prospect> findByPhoneAndClientId(String phone, Long clientId) {
        String normalizedPhone = PhoneUtils.normalize(phone);
        return prospectRepository.findByPhoneAndClientId(normalizedPhone, clientId);
    }

    public List<Prospect> findByClientId(Long clientId) {
        return prospectRepository.findByClientId(clientId);
    }

    @Transactional
    public Prospect createProspect(Long clientId, String phone, String name) {
        String normalizedPhone = PhoneUtils.normalize(phone);

        // Check if prospect already exists
        if (prospectRepository.findByPhoneAndClientId(normalizedPhone, clientId).isPresent()) {
            throw new BusinessException("Prospect with phone " + phone + " already exists");
        }

        // Check if user already exists with this phone
        if (userRepository.findByPhoneAndClientId(normalizedPhone, clientId).isPresent()) {
            throw new BusinessException("User with phone " + phone + " already exists");
        }

        Prospect prospect = new Prospect();
        prospect.setClientId(clientId);
        prospect.setPhone(normalizedPhone);
        prospect.setName(name);
        prospect.setStatus(Status.ACTIVE);
        prospect.setUpgradedToUser(false);

        return prospectRepository.save(prospect);
    }

    @Transactional
    public Prospect updateProspect(Long id, String name, Status status) {
        Prospect prospect = findById(id);

        if (name != null) prospect.setName(name);
        if (status != null) prospect.setStatus(status);

        return prospectRepository.save(prospect);
    }

    @Transactional
    public void deleteProspect(Long id) {
        Prospect prospect = findById(id);

        if (prospect.getUpgradedToUser()) {
            throw new BusinessException("Cannot delete prospect that was upgraded to user");
        }

        prospectRepository.delete(prospect);
    }

    @Transactional
    public Prospect assignToManager(Long prospectId, Long managerId) {
        Prospect prospect = findById(prospectId);

        User manager = userRepository.findById(managerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", managerId));

        prospect.setManager(manager);

        return prospectRepository.save(prospect);
    }

    /**
     * Convert prospect to user (upgrade)
     * Marks the prospect as upgraded_to_user = true
     * Returns the new user ID
     */
    @Transactional
    public Long upgradeToUser(Long prospectId, Long assignToManagerId) {
        Prospect prospect = findById(prospectId);

        if (prospect.getUpgradedToUser()) {
            throw new BusinessException("Prospect already upgraded to user");
        }

        // Check if user already exists with this phone
        if (userRepository.findByPhoneAndClientId(prospect.getPhone(), prospect.getClientId()).isPresent()) {
            throw new BusinessException("User with this phone already exists");
        }

        // Get client entity for the user
        Client client = clientRepository.findById(prospect.getClientId())
                .orElseThrow(() -> new ResourceNotFoundException("Client", prospect.getClientId()));

        // Create new user from prospect - split name into firstName/lastName
        String name = prospect.getName() != null ? prospect.getName() : "";
        String[] nameParts = name.split(" ", 2);
        String firstName = nameParts.length > 0 ? nameParts[0] : "User";
        String lastName = nameParts.length > 1 ? nameParts[1] : prospect.getPhone();

        User user = new User();
        user.setClient(client);
        user.setPhone(prospect.getPhone());
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmail(prospect.getPhone() + "@temp.holape.com"); // Temporary email

        if (assignToManagerId != null) {
            User manager = userRepository.findById(assignToManagerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Manager", assignToManagerId));
            user.setManager(manager);
        } else if (prospect.getManager() != null) {
            user.setManager(prospect.getManager());
        }

        user = userRepository.save(user);

        // Mark prospect as upgraded
        prospect.upgradeToUser();
        prospectRepository.save(prospect);

        log.info("Upgraded prospect {} to user {}", prospectId, user.getId());

        return user.getId();
    }

    public List<Prospect> findByManagerId(Long managerId) {
        return prospectRepository.findByManagerId(managerId);
    }

    /**
     * Delete all non-upgraded prospects for a client
     * PARIDAD RAILS: Admin::ClientsController#destroy_prospects
     * Only deletes prospects where upgraded_to_user = false
     * @return count of deleted prospects
     */
    @Transactional
    public int deleteAllByClientId(Long clientId) {
        List<Prospect> prospects = prospectRepository.findByClientIdAndUpgradedToUserFalse(clientId);
        int count = prospects.size();
        prospectRepository.deleteAll(prospects);
        log.info("Deleted {} non-upgraded prospects for client {}", count, clientId);
        return count;
    }
}
