package org.bluesky.training.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P01：稳定错误信封 code/message/fields/warnings/requestId（详细设计 9.1）。 */
class V2ApiExceptionHandlerTest {

    private MockMvc mockMvc;

    @V2Api
    @RestController
    static class ProbeController {
        @GetMapping("/probe-domain")
        public String probeDomain() {
            throw new V2DomainException("REVISION_CONFLICT", 409,
                    "资源版本已经改变", Collections.singletonList("revision"));
        }

        @GetMapping("/probe-unexpected")
        public String probeUnexpected() {
            throw new IllegalStateException("boom");
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new V2ApiExceptionHandler())
                .build();
    }

    @Test
    void givenDomainFailureThenStableErrorEnvelopeAndRequestIdAreReturned() throws Exception {
        mockMvc.perform(get("/probe-domain").header("X-Request-Id", "req-42"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
                .andExpect(jsonPath("$.message").value("资源版本已经改变"))
                .andExpect(jsonPath("$.fields[0]").value("revision"))
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.warnings").isEmpty())
                .andExpect(jsonPath("$.requestId").value("req-42"));
    }

    @Test
    void givenMissingRequestHeaderWhenFailingThenRequestIdIsGenerated() throws Exception {
        mockMvc.perform(get("/probe-domain"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void givenUnexpectedFailureThenMappedTo500WithoutStackTraceLeak() throws Exception {
        mockMvc.perform(get("/probe-unexpected").header("X-Request-Id", "req-43"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").value("req-43"))
                .andExpect(jsonPath("$.message").value("服务器内部错误"));
    }
}
