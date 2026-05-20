package com.tcn.exile.web.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ScrubListDtoTest {

    @Test
    void testSerializationKeys() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ScrubListDto dto = new ScrubListDto("x", false, ScrubListType.phone);
        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"contentType\":\"phone\""), "JSON should contain 'contentType' key");
        assertFalse(json.contains("\"scrubType\""), "JSON should NOT contain 'scrubType' key");
    }
}
