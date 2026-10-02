package com.dtc.transit.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * Base class for security tests that drive the real filter chain over HTTP.
 *
 * <p>Testing authorization through {@link TestRestTemplate} rather than {@code MockMvc} is deliberate:
 * the token-version filter, the resource-server filter and the error mapping all sit in the servlet
 * chain, and a mocked dispatcher does not run them in the same order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class SecurityWebTest extends PostgisContainerTest {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected SecurityTestSupport support;

    @BeforeEach
    void resetSecurityState() {
        support.reset();
    }
}
