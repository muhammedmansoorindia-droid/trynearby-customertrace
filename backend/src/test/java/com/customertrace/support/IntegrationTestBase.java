package com.customertrace.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.LinkedHashSet;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.customertrace.domain.Creator;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.Role;
import com.customertrace.domain.User;
import com.customertrace.repository.CreatorRepository;
import com.customertrace.repository.RestaurantRepository;
import com.customertrace.repository.RoleRepository;
import com.customertrace.repository.UserRepository;
import com.customertrace.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

/**
 * Base class for integration tests.
 *
 * <p>Runs against the dedicated {@code customertrace_test} schema (see
 * {@code application-test.yml}), so a developer's working data is never read or erased. Flyway
 * creates the schema; Hibernate only validates it.
 *
 * <p>Every test runs inside a transaction that is rolled back afterwards, so fixtures never leak
 * between tests and the suite is order-independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class IntegrationTestBase {

    /** Password for every fixture account; hashed on save, never stored in clear text. */
    protected static final String TEST_PASSWORD = "TestPassw0rd!";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected RoleRepository roleRepository;

    @Autowired
    protected RestaurantRepository restaurantRepository;

    @Autowired
    protected CreatorRepository creatorRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected EntityManager entityManager;

    @BeforeEach
    void ensureRoles() {
        for (Role role : new Role[] {
                new Role("ADMIN", "Administrator", "Platform governance"),
                new Role("RESTAURANT", "Restaurant", "Restaurant partner"),
                new Role("CREATOR", "Creator", "Content creator") }) {
            roleRepository.findByCode(role.getCode())
                    .orElseGet(() -> roleRepository.save(role));
        }
    }

    /** Creates an active user holding the given role. */
    protected User createUser(String userId, String roleCode, String displayName) {
        User user = new User();
        user.setUserId(userId);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
        user.setStatus("ACTIVE");
        user.getRoles().add(roleRepository.findByCode(roleCode).orElseThrow());
        return userRepository.save(user);
    }

    protected Restaurant createRestaurant(String userId, String name, String city) {
        User user = createUser(userId, "RESTAURANT", name);
        Restaurant restaurant = new Restaurant();
        restaurant.setUserId(user.getId());
        restaurant.setName(name);
        restaurant.setSlug(slug(name) + "-" + user.getId());
        restaurant.setCity(city);
        restaurant.setRegion(city);
        restaurant.setAddress(city + ", India");
        return restaurantRepository.save(restaurant);
    }

    protected Creator createCreator(String userId, String name, String handle) {
        User user = createUser(userId, "CREATOR", name);
        Creator creator = new Creator();
        creator.setUserId(user.getId());
        creator.setDisplayName(name);
        creator.setHandle(handle);
        creator.setCity("Kochi");
        creator.setFollowers(10_000);
        return creatorRepository.save(creator);
    }

    /** Issues a token directly, so a test need not round-trip the login endpoint. */
    protected String tokenFor(User user) {
        return jwtService.createAccessToken(user.getId(), user.getUserId(),
                new LinkedHashSet<>(user.getRoleCodes()).stream().sorted().toList());
    }

    /** Signs in over HTTP, exercising the real login path. */
    protected String loginAndGetToken(String userId, String portal) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","password":"%s","portal":"%s"}
                                """.formatted(userId, TEST_PASSWORD, portal)))
                .andReturn();
        return parse(result).path("accessToken").asText();
    }

    /** Adds the bearer header to a request. */
    protected MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder,
                                                     String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    /** Parses a response body as JSON for assertions. */
    protected JsonNode parse(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Forces a flush so a queued insert is visible to the next query in the same test. */
    protected void flush() {
        entityManager.flush();
    }

    private String slug(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}