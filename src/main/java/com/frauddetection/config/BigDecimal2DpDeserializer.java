package com.frauddetection.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Custom Jackson deserializer that reads BigDecimal values and sets scale to exactly 2 decimal places.
 * This ensures round-trip consistency: serialize(100) → "100.00" → deserialize → BigDecimal(100.00) with scale 2.
 */
public class BigDecimal2DpDeserializer extends JsonDeserializer<BigDecimal> {

    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        BigDecimal value = p.getDecimalValue();
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
