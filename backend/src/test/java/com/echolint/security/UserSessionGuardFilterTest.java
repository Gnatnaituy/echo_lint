package com.echolint.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.echolint.domain.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** 会话纪元过滤器：纪元不匹配 → 401 + 销毁会话；匹配/匿名/非会话主体 → 放行；登录入口与静态资源豁免范围。 */
class UserSessionGuardFilterTest {

    private final SessionEpochService epochService = new SessionEpochService();
    private final UserSessionGuardFilter filter =
            new UserSessionGuardFilter(
                    epochService, new JsonAuthenticationEntryPoint(new ObjectMapper()));

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("GET", "/api/recordings/1");
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username) {
        AppUserDetails details =
                new AppUserDetails(1L, username, "hash", username, UserRole.REVIEWER, true);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                details, null, details.getAuthorities()));
    }

    private HttpSession sessionWithEpoch(Object epoch) {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionEpochService.SESSION_ATTRIBUTE)).thenReturn(epoch);
        request.setSession(session);
        return session;
    }

    @Test
    void anonymousRequestPassesThrough() throws Exception {
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void nonSessionPrincipalPassesThrough() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("api-key:ci", null, List.of()));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void missingSessionPassesThroughToAuthenticationLayer() throws Exception {
        loginAs("alice");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void missingEpochRejectsAndInvalidatesSession() throws Exception {
        loginAs("alice");
        HttpSession session = sessionWithEpoch(null);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(session).invalidate();
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":401"), "应返回统一的 JSON 401 结构");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void staleEpochRejectsAfterAccountStateChange() throws Exception {
        loginAs("alice");
        HttpSession session = sessionWithEpoch(0L);
        epochService.bump("alice"); // 模拟禁用/降级/改密后纪元 +1

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(session).invalidate();
        assertEquals(401, response.getStatus());
    }

    @Test
    void matchingEpochPassesThrough() throws Exception {
        loginAs("alice");
        epochService.bump("alice");
        sessionWithEpoch(epochService.current("alice"));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void loginEndpointIsNotFilteredEvenWithStaleSession() throws Exception {
        loginAs("alice");
        sessionWithEpoch(0L);
        epochService.bump("alice");
        MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/api/auth/login");

        filter.doFilter(loginRequest, response, chain);

        verify(chain).doFilter(loginRequest, response);
    }

    @Test
    void uploadsAudioStreamIsAlsoGuarded() throws Exception {
        loginAs("alice");
        epochService.bump("alice");
        MockHttpServletRequest uploadsRequest =
                new MockHttpServletRequest("GET", "/uploads/abc.mp3");
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionEpochService.SESSION_ATTRIBUTE)).thenReturn(0L);
        uploadsRequest.setSession(session);

        filter.doFilter(uploadsRequest, response, chain);

        verify(chain, never()).doFilter(uploadsRequest, response);
        verify(session).invalidate();
        assertEquals(401, response.getStatus());
    }

    @Test
    void nonApiRequestIsNotFiltered() throws Exception {
        loginAs("alice");
        epochService.bump("alice");
        MockHttpServletRequest actuatorRequest =
                new MockHttpServletRequest("GET", "/actuator/health");

        filter.doFilter(actuatorRequest, response, chain);

        verify(chain).doFilter(actuatorRequest, response);
    }
}
