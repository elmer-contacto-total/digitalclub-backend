package com.digitalgroup.holape.websocket;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/**
 * Custom HandshakeHandler that creates a Principal from the userId
 * stored in session attributes by WebSocketAuthInterceptor.
 *
 * Required for convertAndSendToUser() to route messages to the correct session.
 */
public class UserHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(
            ServerHttpRequest request,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        Long userId = (Long) attributes.get("userId");
        if (userId != null) {
            return () -> userId.toString();
        }
        return super.determineUser(request, wsHandler, attributes);
    }
}
