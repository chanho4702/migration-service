package com.platform.migrationservice.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게이트웨이 관리자 대시보드가 인증 없이 프로브하는 두 경로. 노출은 health·info뿐이고,
 * 원본 DC·org gRPC·위키 import는 인디케이터에 없다 — 프로브가 이관 대상에 부하를 주면 안 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ActuatorHealthTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void healthIsPublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // show-details: always — 게이트웨이가 components.db를 읽는다
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void infoIsPublic() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
