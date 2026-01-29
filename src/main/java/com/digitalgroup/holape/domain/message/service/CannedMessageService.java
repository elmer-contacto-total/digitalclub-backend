package com.digitalgroup.holape.domain.message.service;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.message.entity.CannedMessage;
import com.digitalgroup.holape.domain.message.repository.CannedMessageRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Canned Message Service
 * Manages predefined quick response messages
 *
 * PARIDAD RAILS: canned_messages_controller.rb
 * Campos: message, client_global, status, client_id, user_id
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CannedMessageService {

    private final CannedMessageRepository cannedMessageRepository;
    private final ClientRepository clientRepository;
    private final UserRepository userRepository;

    public CannedMessage findById(Long id) {
        return cannedMessageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CannedMessage", id));
    }

    /**
     * Find canned messages for a client
     * PARIDAD RAILS: CannedMessage.where(client_id: client_id)
     */
    public List<CannedMessage> findByClient(Long clientId) {
        return cannedMessageRepository.findByClientIdAndStatus(clientId, Status.ACTIVE);
    }

    /**
     * Find canned messages visible to a user
     * PARIDAD RAILS: user's own messages OR client_global = true
     */
    public List<CannedMessage> findVisibleToUser(Long clientId, Long userId) {
        return cannedMessageRepository.findByClientIdAndUserIdOrClientGlobal(clientId, userId);
    }

    /**
     * Find global canned messages for a client
     */
    public List<CannedMessage> findGlobalByClient(Long clientId) {
        return cannedMessageRepository.findByClientIdAndClientGlobalTrue(clientId);
    }

    /**
     * Create a new canned message
     * PARIDAD RAILS: canned_messages_controller.rb#create
     */
    @Transactional
    public CannedMessage create(Long clientId, Long userId, String message, Boolean clientGlobal) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Client", clientId));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        CannedMessage cannedMessage = new CannedMessage();
        cannedMessage.setClient(client);
        cannedMessage.setUser(user);
        cannedMessage.setMessage(message);
        cannedMessage.setClientGlobal(clientGlobal != null ? clientGlobal : false);
        cannedMessage.setStatus(Status.ACTIVE);

        return cannedMessageRepository.save(cannedMessage);
    }

    /**
     * Update a canned message
     * PARIDAD RAILS: canned_messages_controller.rb#update
     */
    @Transactional
    public CannedMessage update(Long id, String message, Boolean clientGlobal, Status status) {
        CannedMessage cannedMessage = findById(id);

        if (message != null) {
            cannedMessage.setMessage(message);
        }
        if (clientGlobal != null) {
            cannedMessage.setClientGlobal(clientGlobal);
        }
        if (status != null) {
            cannedMessage.setStatus(status);
        }

        return cannedMessageRepository.save(cannedMessage);
    }

    /**
     * Delete (hard delete) a canned message
     * PARIDAD RAILS: canned_messages_controller.rb#destroy
     */
    @Transactional
    public void delete(Long id) {
        CannedMessage cannedMessage = findById(id);
        cannedMessageRepository.delete(cannedMessage);
    }

    /**
     * Soft delete - set status to inactive
     */
    @Transactional
    public void deactivate(Long id) {
        CannedMessage cannedMessage = findById(id);
        cannedMessage.setStatus(Status.INACTIVE);
        cannedMessageRepository.save(cannedMessage);
    }

    /**
     * Count by client
     */
    public long countByClient(Long clientId) {
        return cannedMessageRepository.countByClientId(clientId);
    }
}
