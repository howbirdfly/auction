package com.auction.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:auction-security;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "auction.cache.redis.enabled=false",
        "auction.persistence.rabbitmq.enabled=false"
})
@AutoConfigureMockMvc
class AuthenticationAuthorizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousUsersCanBrowseAuctionsButNotBid() throws Exception {
        mockMvc.perform(get("/api/auctions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/auctions/AR-1001/bids")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "anonymous-bid",
                                  "userId": "u10001",
                                  "nickname": "Bidder A",
                                  "amount": 999.00
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void loginCreatesSessionAndMeReturnsAuthenticatedUser() throws Exception {
        MockHttpSession session = login("u10001", "123456");

        mockMvc.perform(get("/api/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account").value("u10001"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void invalidPasswordReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "u10001",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void registrationCreatesAnAuthenticatedSession() throws Exception {
        String account = "user-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "%s",
                                  "password": "123456",
                                  "nickname": "New Bidder"
                                }
                                """.formatted(account)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account").value(account))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(get("/api/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account").value(account));
    }

    @Test
    void regularUserCannotImpersonateAnotherBidder() throws Exception {
        MockHttpSession session = login("u10001", "123456");

        mockMvc.perform(post("/api/auctions/AR-1001/bids")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "impersonated-bid",
                                  "userId": "ava_host",
                                  "nickname": "Host Ava",
                                  "amount": 999.00
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("request user does not match the authenticated account"));
    }

    @Test
    void regularUserCannotReadAnotherUsersWallet() throws Exception {
        MockHttpSession session = login("u10001", "123456");

        mockMvc.perform(get("/api/users/U-10001/wallet-transactions").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("cannot access another user's data"));
    }

    @Test
    void adminEndpointsRequireAdminRole() throws Exception {
        MockHttpSession userSession = login("u10001", "123456");
        mockMvc.perform(get("/api/users").session(userSession))
                .andExpect(status().isForbidden());

        MockHttpSession adminSession = login("admin", "admin123456");
        mockMvc.perform(get("/api/users").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void authenticatedUserMaySubmitTheirOwnBidIdentity() throws Exception {
        MockHttpSession session = login("u10001", "123456");

        mockMvc.perform(post("/api/auctions/AR-1004/bids")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "own-bid-identity",
                                  "userId": "u10001",
                                  "nickname": "Bidder A",
                                  "amount": 999.00
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("请先报名竞拍并冻结保证金"));
    }

    private MockHttpSession login(String account, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "%s",
                                  "password": "%s"
                                }
                                """.formatted(account, password)))
                .andExpect(status().isOk())
                .andReturn();

        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
