package com.digitalgroup.holape.config;

import com.digitalgroup.holape.websocket.UserHandshakeHandler;
import com.digitalgroup.holape.websocket.WebSocketAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket Configuration
 * Configures STOMP over WebSocket with SockJS fallback
 * Equivalent to Rails ActionCable configuration
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor authInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable simple broker for topics and queues
        // /topic - for broadcast messages (all subscribers receive)
        // /queue - for point-to-point messages (single subscriber)
        config.enableSimpleBroker("/topic", "/queue");

        // Prefix for messages from clients to server
        config.setApplicationDestinationPrefixes("/app");

        // Prefix for user-specific destinations
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        UserHandshakeHandler handshakeHandler = new UserHandshakeHandler();

        // WebSocket endpoint with SockJS fallback and JWT authentication
        registry.addEndpoint("/ws")
                .setHandshakeHandler(handshakeHandler)
                .addInterceptors(authInterceptor)
                .setAllowedOriginPatterns("*")
                .withSockJS();

        // Pure WebSocket endpoint (without SockJS)
        registry.addEndpoint("/websocket")
                .setHandshakeHandler(handshakeHandler)
                .addInterceptors(authInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
