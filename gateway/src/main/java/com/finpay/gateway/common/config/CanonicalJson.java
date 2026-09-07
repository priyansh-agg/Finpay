package com.finpay.gateway.common.config;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

/**
 * Serialises a request body canonically (keys sorted) so the same logical payload always
 * produces the same string — and therefore the same idempotency request hash — regardless
 * of field order. Deliberately NOT an {@code ObjectMapper} bean, so it does not disable
 * Spring Boot's auto-configured primary mapper.
 */
@Component
public class CanonicalJson {

    private final ObjectMapper mapper;

    public CanonicalJson() {
        this.mapper = new ObjectMapper().findAndRegisterModules();
        this.mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        this.mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    public String stringify(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unserialisable request body", e);
        }
    }
}
