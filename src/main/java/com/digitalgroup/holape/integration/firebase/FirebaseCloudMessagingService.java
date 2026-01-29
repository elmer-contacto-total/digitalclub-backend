package com.digitalgroup.holape.integration.firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Firebase Cloud Messaging Service
 * Handles push notifications to mobile devices
 */
@Slf4j
@Service
public class FirebaseCloudMessagingService {

    @Value("${firebase.credentials-file:firebase-service-account.json}")
    private String credentialsFile;

    @Value("${firebase.enabled:false}")
    private boolean enabled;

    private FirebaseMessaging firebaseMessaging;

    @PostConstruct
    public void initialize() {
        if (!enabled) {
            log.info("Firebase Cloud Messaging is disabled");
            return;
        }

        try {
            ClassPathResource resource = new ClassPathResource(credentialsFile);

            if (resource.exists()) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(resource.getInputStream()))
                        .build();

                if (FirebaseApp.getApps().isEmpty()) {
                    FirebaseApp.initializeApp(options);
                }

                firebaseMessaging = FirebaseMessaging.getInstance();
                log.info("Firebase Cloud Messaging initialized successfully");
            } else {
                log.warn("Firebase credentials file not found: {}", credentialsFile);
            }
        } catch (IOException e) {
            log.error("Failed to initialize Firebase", e);
        }
    }

    /**
     * Send push notification to a single device
     */
    public String sendNotification(String token, String title, String body, Map<String, String> data) {
        if (!isEnabled()) {
            log.debug("Firebase disabled, skipping notification");
            return null;
        }

        try {
            Message.Builder messageBuilder = Message.builder()
                    .setToken(token)
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build());

            if (data != null && !data.isEmpty()) {
                messageBuilder.putAllData(data);
            }

            // Add Android specific config
            messageBuilder.setAndroidConfig(AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .setNotification(AndroidNotification.builder()
                            .setSound("default")
                            .setClickAction("FLUTTER_NOTIFICATION_CLICK")
                            .build())
                    .build());

            // Add iOS specific config
            messageBuilder.setApnsConfig(ApnsConfig.builder()
                    .setAps(Aps.builder()
                            .setSound("default")
                            .setBadge(1)
                            .build())
                    .build());

            String response = firebaseMessaging.send(messageBuilder.build());
            log.debug("Sent FCM message: {}", response);
            return response;

        } catch (FirebaseMessagingException e) {
            log.error("Failed to send FCM notification", e);
            handleFcmError(token, e);
            return null;
        }
    }

    /**
     * Send push notification to multiple devices
     */
    public BatchResponse sendMulticast(List<String> tokens, String title, String body, Map<String, String> data) {
        if (!isEnabled() || tokens.isEmpty()) {
            return null;
        }

        try {
            MulticastMessage.Builder messageBuilder = MulticastMessage.builder()
                    .addAllTokens(tokens)
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build());

            if (data != null && !data.isEmpty()) {
                messageBuilder.putAllData(data);
            }

            BatchResponse response = firebaseMessaging.sendEachForMulticast(messageBuilder.build());

            log.info("Sent multicast: {} success, {} failures",
                    response.getSuccessCount(), response.getFailureCount());

            // Handle failures
            if (response.getFailureCount() > 0) {
                handleMulticastFailures(tokens, response);
            }

            return response;

        } catch (FirebaseMessagingException e) {
            log.error("Failed to send multicast notification", e);
            return null;
        }
    }

    /**
     * Send data-only message (silent notification)
     */
    public String sendDataMessage(String token, Map<String, String> data) {
        if (!isEnabled()) {
            return null;
        }

        try {
            Message message = Message.builder()
                    .setToken(token)
                    .putAllData(data)
                    .build();

            String response = firebaseMessaging.send(message);
            log.debug("Sent FCM data message: {}", response);
            return response;

        } catch (FirebaseMessagingException e) {
            log.error("Failed to send FCM data message", e);
            return null;
        }
    }

    /**
     * Send notification to a topic
     */
    public String sendToTopic(String topic, String title, String body, Map<String, String> data) {
        if (!isEnabled()) {
            return null;
        }

        try {
            Message.Builder messageBuilder = Message.builder()
                    .setTopic(topic)
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build());

            if (data != null && !data.isEmpty()) {
                messageBuilder.putAllData(data);
            }

            String response = firebaseMessaging.send(messageBuilder.build());
            log.debug("Sent FCM topic message to {}: {}", topic, response);
            return response;

        } catch (FirebaseMessagingException e) {
            log.error("Failed to send FCM topic notification", e);
            return null;
        }
    }

    /**
     * Subscribe token to topic
     */
    public void subscribeToTopic(String token, String topic) {
        if (!isEnabled()) {
            return;
        }

        try {
            firebaseMessaging.subscribeToTopic(List.of(token), topic);
            log.debug("Subscribed token to topic: {}", topic);
        } catch (FirebaseMessagingException e) {
            log.error("Failed to subscribe to topic", e);
        }
    }

    /**
     * Unsubscribe token from topic
     */
    public void unsubscribeFromTopic(String token, String topic) {
        if (!isEnabled()) {
            return;
        }

        try {
            firebaseMessaging.unsubscribeFromTopic(List.of(token), topic);
            log.debug("Unsubscribed token from topic: {}", topic);
        } catch (FirebaseMessagingException e) {
            log.error("Failed to unsubscribe from topic", e);
        }
    }

    private boolean isEnabled() {
        return enabled && firebaseMessaging != null;
    }

    private void handleFcmError(String token, FirebaseMessagingException e) {
        MessagingErrorCode errorCode = e.getMessagingErrorCode();

        if (errorCode == MessagingErrorCode.UNREGISTERED ||
            errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
            // Token is invalid, should be removed from database
            log.warn("Invalid FCM token, should be removed: {}", token);
            // TODO: Publish event to remove token from user's device list
        }
    }

    private void handleMulticastFailures(List<String> tokens, BatchResponse response) {
        List<SendResponse> responses = response.getResponses();

        for (int i = 0; i < responses.size(); i++) {
            SendResponse sendResponse = responses.get(i);
            if (!sendResponse.isSuccessful()) {
                FirebaseMessagingException exception = sendResponse.getException();
                if (exception != null) {
                    handleFcmError(tokens.get(i), exception);
                }
            }
        }
    }
}
