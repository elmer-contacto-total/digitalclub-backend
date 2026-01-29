package com.digitalgroup.holape.integration.firebase;

import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.user.entity.DeviceInfo;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.DeviceInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Push Notification Service
 * High-level service for sending push notifications
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationService {

    private final FirebaseCloudMessagingService fcmService;
    private final DeviceInfoRepository deviceInfoRepository;

    /**
     * Send new message notification to user
     */
    @Async
    public void notifyNewMessage(User recipient, Message message) {
        List<String> tokens = getActiveFcmTokens(recipient.getId());

        if (tokens.isEmpty()) {
            log.debug("No FCM tokens for user {}", recipient.getId());
            return;
        }

        String title = "Nuevo mensaje";
        String body = truncateContent(message.getContent(), 100);

        Map<String, String> data = new HashMap<>();
        data.put("type", "new_message");
        data.put("message_id", message.getId().toString());
        if (message.getTicket() != null) {
            data.put("ticket_id", message.getTicket().getId().toString());
        }

        if (tokens.size() == 1) {
            fcmService.sendNotification(tokens.get(0), title, body, data);
        } else {
            fcmService.sendMulticast(tokens, title, body, data);
        }

        log.debug("Sent new message notification to user {}", recipient.getId());
    }

    /**
     * Send ticket assignment notification
     */
    @Async
    public void notifyTicketAssigned(User agent, Ticket ticket) {
        List<String> tokens = getActiveFcmTokens(agent.getId());

        if (tokens.isEmpty()) {
            return;
        }

        String title = "Ticket asignado";
        String body = String.format("Se te ha asignado el ticket #%d", ticket.getId());

        Map<String, String> data = new HashMap<>();
        data.put("type", "ticket_assigned");
        data.put("ticket_id", ticket.getId().toString());
        if (ticket.getUser() != null) {
            data.put("user_name", ticket.getUser().getFullName());
        }

        fcmService.sendMulticast(tokens, title, body, data);
    }

    /**
     * Send require response alert notification
     */
    @Async
    public void notifyRequireResponse(User agent, Ticket ticket) {
        List<String> tokens = getActiveFcmTokens(agent.getId());

        if (tokens.isEmpty()) {
            return;
        }

        String title = "Respuesta requerida";
        String body = String.format("El ticket #%d requiere respuesta", ticket.getId());

        Map<String, String> data = new HashMap<>();
        data.put("type", "require_response");
        data.put("ticket_id", ticket.getId().toString());
        data.put("priority", "high");

        fcmService.sendMulticast(tokens, title, body, data);
    }

    /**
     * Send notification to all agents of a client
     */
    @Async
    public void notifyClientAgents(Long clientId, String title, String body, Map<String, String> data) {
        List<DeviceInfo> devices = deviceInfoRepository.findActiveByClientId(clientId);

        List<String> tokens = devices.stream()
                .map(DeviceInfo::getFcmToken)
                .filter(t -> t != null && !t.isEmpty())
                .collect(Collectors.toList());

        if (!tokens.isEmpty()) {
            fcmService.sendMulticast(tokens, title, body, data);
        }
    }

    /**
     * Send silent data notification for background sync
     */
    @Async
    public void sendSyncNotification(Long userId, String syncType) {
        List<String> tokens = getActiveFcmTokens(userId);

        if (tokens.isEmpty()) {
            return;
        }

        Map<String, String> data = new HashMap<>();
        data.put("type", "background_sync");
        data.put("sync_type", syncType);
        data.put("timestamp", String.valueOf(System.currentTimeMillis()));

        for (String token : tokens) {
            fcmService.sendDataMessage(token, data);
        }
    }

    /**
     * Subscribe user devices to client topic
     */
    public void subscribeUserToClientTopic(Long userId, Long clientId) {
        List<String> tokens = getActiveFcmTokens(userId);
        String topic = "client_" + clientId;

        for (String token : tokens) {
            fcmService.subscribeToTopic(token, topic);
        }
    }

    /**
     * Unsubscribe user devices from client topic
     */
    public void unsubscribeUserFromClientTopic(Long userId, Long clientId) {
        List<String> tokens = getActiveFcmTokens(userId);
        String topic = "client_" + clientId;

        for (String token : tokens) {
            fcmService.unsubscribeFromTopic(token, topic);
        }
    }

    private List<String> getActiveFcmTokens(Long userId) {
        return deviceInfoRepository.findByUserId(userId).stream()
                .filter(d -> d.getFcmToken() != null && !d.getFcmToken().isEmpty())
                .filter(d -> d.getActive() != null && d.getActive())
                .map(DeviceInfo::getFcmToken)
                .collect(Collectors.toList());
    }

    private String truncateContent(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength - 3) + "...";
    }
}
