package com.frauddetection.repository;

/**
 * Publishes decision events to EventBridge asynchronously.
 * Uses fire-and-forget semantics to avoid blocking the response path.
 */
public interface EventPublisher {
    void publishDecisionMade(DecisionEvent event);
}
