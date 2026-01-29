package com.digitalgroup.holape.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket Event Listener
 * Handles connection/disconnection events
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final WebSocketService webSocketService;

    // Track connected users: userId -> sessionId
    private final Map<Long, String> connectedUsers = new ConcurrentHashMap<>();

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = headerAccessor.getUser();

        if (user != null) {
            try {
                Long userId = Long.parseLong(user.getName());
                String sessionId = headerAccessor.getSessionId();

                connectedUsers.put(userId, sessionId);
                webSocketService.sendOnlineStatus(userId, true);

                log.info("User {} connected via WebSocket (session: {})", userId, sessionId);
            } catch (NumberFormatException e) {
                log.warn("Invalid user principal: {}", user.getName());
            }
        }
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = headerAccessor.getUser();

        if (user != null) {
            try {
                Long userId = Long.parseLong(user.getName());

                connectedUsers.remove(userId);
                webSocketService.sendOnlineStatus(userId, false);

                log.info("User {} disconnected from WebSocket", userId);
            } catch (NumberFormatException e) {
                log.warn("Invalid user principal on disconnect: {}", user.getName());
            }
        }
    }

    @EventListener
    public void handleWebSocketSubscribeListener(SessionSubscribeEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = headerAccessor.getUser();
        String destination = headerAccessor.getDestination();

        if (user != null && destination != null) {
            log.debug("User {} subscribed to {}", user.getName(), destination);
        }
    }

    /**
     * Check if user is currently connected
     */
    public boolean isUserOnline(Long userId) {
        return connectedUsers.containsKey(userId);
    }

    /**
     * Get all connected user IDs
     */
    public java.util.Set<Long> getConnectedUsers() {
        return connectedUsers.keySet();
    }

    /**
     * Get session ID for a user
     */
    public String getSessionId(Long userId) {
        return connectedUsers.get(userId);
    }
}
