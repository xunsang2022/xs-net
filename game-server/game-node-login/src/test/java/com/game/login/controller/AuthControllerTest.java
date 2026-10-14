package com.game.login.controller;

import com.game.comment.util.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret";

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(new JwtUtil(SECRET, 60000))).build();
    }

    @Test
    void issueThenVerifyRoundTrip() throws Exception {
        MvcResult issueResult = mockMvc.perform(post("/api/auth/issue")
                        .header("X-Channel-Id", "channel-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":42,\"playerName\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNumber())
                .andReturn();

        JsonNode issueBody = objectMapper.readTree(issueResult.getResponse().getContentAsString());
        String token = issueBody.get("token").asText();

        mockMvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.playerId").value(42))
                .andExpect(jsonPath("$.channelId").value("channel-123"));
    }

    @Test
    void verifyRejectsInvalidToken() throws Exception {
        mockMvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"not-a-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void issueGeneratesChannelIdWhenHeaderMissing() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":7,\"playerName\":\"bob\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.channelId").isNotEmpty());
    }

    @Test
    void issueRejectsMissingPlayerId() throws Exception {
        mockMvc.perform(post("/api/auth/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerName\":\"bob\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void issueRejectsNonPositivePlayerId() throws Exception {
        mockMvc.perform(post("/api/auth/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void issueIsRateLimitedPerIp() throws Exception {
        for (int i = 0; i < AuthController.ISSUE_MAX_REQUESTS; i++) {
            mockMvc.perform(post("/api/auth/issue")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"playerId\":1}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/auth/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":1}"))
                .andExpect(status().isTooManyRequests());
    }
}
