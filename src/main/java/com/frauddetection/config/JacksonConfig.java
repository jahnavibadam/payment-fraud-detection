package com.frauddetection.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;

/**
 * Jackson ObjectMapper configuration for the fraud detection system.
 *
 * Configuration:
 * - Enums serialize as string names (via @JsonFormat on each enum)
 * - Null optional fields are preserved (not substituted with defaults)
 * - Instant and Duration handled via jackson-datatype-jsr310
 * - BigDecimal serialized to exactly 2 decimal places
 */
public final class JacksonConfig {

    private JacksonConfig() {
        // utility class
    }

    public static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Register JSR310 module for Instant and Duration support
        mapper.registerModule(new JavaTimeModule());

        // Register JDK8 module for Optional support
        mapper.registerModule(new Jdk8Module());

        // Register custom BigDecimal module for 2 decimal place serialization
        SimpleModule bigDecimalModule = new SimpleModule("BigDecimal2DpModule");
        bigDecimalModule.addSerializer(BigDecimal.class, new BigDecimal2DpSerializer());
        bigDecimalModule.addDeserializer(BigDecimal.class, new BigDecimal2DpDeserializer());
        mapper.registerModule(bigDecimalModule);

        // Enums serialize as string names via @JsonFormat(shape = Shape.STRING) on each enum

        // Write dates as ISO-8601 strings, not timestamps
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // Write durations as ISO-8601 strings, not numeric values
        mapper.configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false);

        // Serialize BigDecimal as plain string (avoid scientific notation)
        mapper.configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, true);

        // Do not fail on unknown properties during deserialization
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }
}
