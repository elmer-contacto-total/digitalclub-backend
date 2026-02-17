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
import java.util.Set;
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

    // Track connected users: userId -> set of sessionIds (supports multiple sessions per user)
    private final Map<Long, Set<String>> connectedUsers = new ConcurrentHashMap<>();

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = headerAccessor.getUser();

        if (user != null) {
            try {
                Long userId = Long.parseLong(user.getName());
                String sessionId = headerAccessor.getSessionId();

                connectedUsers.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
                // Broadcast online only on first session for this user
                if (connectedUsers.get(userId).size() == 1) {
                    webSocketService.sendOnlineStatus(userId, true);
                }

                log.info("User {} connected via WebSocket (session: {}, total sessions: {})",
                        userId, sessionId, connectedUsers.get(userId).size());
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

                Set<String> sessions = connectedUsers.get(userId);
                if (sessions != null) {
                    sessions.remove(headerAccessor.getSessionId());
                    if (sessions.isEmpty()) {
                        connectedUsers.remove(userId);
                        webSocketService.sendOnlineStatus(userId, false);
                    }
                }

                log.info("User {} disconnected from WebSocket (remaining sessions: {})",
                        userId, sessions != null ? sessions.size() : 0);
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
     * Check if user is currently connected (has at least one active session)
     */
    public boolean isUserOnline(Long userId) {
        Set<String> sessions = connectedUsers.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    /**
     * Get all connected user IDs
     */
    public Set<Long> getConnectedUsers() {
        return connectedUsers.keySet();
    }

    /**
     * Get session IDs for a user (may have multiple sessions)
     */
    public Set<String> getSessionIds(Long userId) {
        return connectedUsers.getOrDefault(userId, Set.of());
    }
}
