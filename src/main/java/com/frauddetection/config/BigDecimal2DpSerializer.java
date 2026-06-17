package com.frauddetection.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Custom Jackson serializer that always writes BigDecimal values with exactly 2 decimal places.
 * For example: 100 → 100.00, 99.5 → 99.50, 123.456 → 123.46
 */
public class BigDecimal2DpSerializer extends JsonSerializer<BigDecimal> {

    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else {
            gen.writeNumber(value.setScale(2, RoundingMode.HALF_UP));
        }
    }
}
