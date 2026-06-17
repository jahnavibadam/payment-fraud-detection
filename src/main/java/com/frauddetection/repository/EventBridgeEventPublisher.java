package com.frauddetection.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.config.JacksonConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;

import java.util.concurrent.CompletableFuture;

/**
 * EventBridge implementation of EventPublisher.
 * Publishes DecisionMade events asynchronously with fire-and-forget semantics.
 * Failures are logged locally and never block the response path.
 */
public class EventBridgeEventPublisher implements EventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(EventBridgeEventPublisher.class);

    private static final String DEFAULT_EVENT_BUS_NAME = "fraud-detection";
    private static final String EVENT_SOURCE = "com.frauddetection";
    private static final String DETAIL_TYPE = "DecisionMade";

    private final EventBridgeClient eventBridgeClient;
    private final ObjectMapper objectMapper;
    private final String eventBusName;

    public EventBridgeEventPublisher(EventBridgeClient eventBridgeClient) {
        this(eventBridgeClient, JacksonConfig.createObjectMapper(),
             System.getenv().getOrDefault("EVENT_BUS_NAME", DEFAULT_EVENT_BUS_NAME));
    }

    public EventBridgeEventPublisher(EventBridgeClient eventBridgeClient, ObjectMapper objectMapper, String eventBusName) {
        this.eventBridgeClient = eventBridgeClient;
        this.objectMapper = objectMapper;
        this.eventBusName = eventBusName;
    }

    @Override
    public void publishDecisionMade(DecisionEvent event) {
        CompletableFuture.runAsync(() -> doPublish(event))
            .exceptionally(throwable -> {
                logger.error("Unexpected error during async event publish for messageId={}: {}",
                    event.messageId(), throwable.getMessage(), throwable);
                return null;
            });
    }

    private void doPublish(DecisionEvent event) {
        try {
            String detailJson = objectMapper.writeValueAsString(event);

            PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(eventBusName)
                .source(EVENT_SOURCE)
                .detailType(DETAIL_TYPE)
                .detail(detailJson)
                .build();

            PutEventsRequest request = PutEventsRequest.builder()
                .entries(entry)
                .build();

            PutEventsResponse response = eventBridgeClient.putEvents(request);

            if (response.failedEntryCount() > 0) {
                for (PutEventsResultEntry resultEntry : response.entries()) {
                    if (resultEntry.errorCode() != null) {
                        logger.error("Failed to publish DecisionMade event for messageId={}: errorCode={}, errorMessage={}",
                            event.messageId(), resultEntry.errorCode(), resultEntry.errorMessage());
                    }
                }
            } else {
                logger.info("Published DecisionMade event for messageId={}", event.messageId());
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize DecisionEvent for messageId={}: {}",
                event.messageId(), e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Failed to publish DecisionMade event for messageId={}: {}",
                event.messageId(), e.getMessage(), e);
        }
    }
}
