package com.game.gateway.filter;

import com.game.comment.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JwtAuthFilterTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret";

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 60000);
    }

    @Test
    void localModeAcceptsValidToken() throws Exception {
        JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, RestClient.builder().build(), "local");
        MockHttpServletRequest request = requestWithToken(jwtUtil.generateToken("42", "channel-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute("playerId")).isEqualTo("42");
        assertThat(request.getAttribute("channelId")).isEqualTo("channel-1");
    }

    @Test
    void localModeRejectsMissingHeader() throws Exception {
        JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, RestClient.builder().build(), "local");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/players/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void localModeRejectsInvalidToken() throws Exception {
        JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, RestClient.builder().build(), "local");
        MockHttpServletRequest request = requestWithToken("not-a-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void remoteModeAcceptsVerifiedToken() throws Exception {
        RestClient restClient = RestClient.builder().baseUrl("http://login.test").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
        JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, restClient, "remote");

        server.expect(requestTo("http://login.test/api/auth/verify"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"valid\":true,\"playerId\":42,\"channelId\":\"channel-1\"}",
                        MediaType.APPLICATION_JSON));

        MockHttpServletRequest request = requestWithToken("remote-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute("playerId")).isEqualTo("42");
        assertThat(request.getAttribute("channelId")).isEqualTo("channel-1");
        server.verify();
    }

    @Test
    void remoteModeFailsClosedOnInvalidResponse() throws Exception {
        RestClient restClient = RestClient.builder().baseUrl("http://login.test").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
        JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, restClient, "remote");

        server.expect(requestTo("http://login.test/api/auth/verify"))
                .andRespond(withSuccess("{\"valid\":false}", MediaType.APPLICATION_JSON));

        MockHttpServletRequest request = requestWithToken("remote-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        server.verify();
    }

    @Test
    void remoteModeFailsClosedOnAuthServiceError() throws Exception {
        RestClient restClient = RestClient.builder().baseUrl("http://login.test").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
        JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, restClient, "remote");

        server.expect(requestTo("http://login.test/api/auth/verify"))
                .andRespond(withServerError());

        MockHttpServletRequest request = requestWithToken("remote-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(chain.getRequest()).isNull();
        server.verify();
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/players/logout");
        request.addHeader("Authorization", token);
        return request;
    }
}
