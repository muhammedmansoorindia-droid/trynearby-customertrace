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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.customertrace.config.SecurityConfig;
import com.customertrace.domain.Creator;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.RestaurantCreatorAssignment;
import com.customertrace.domain.Role;
import com.customertrace.domain.User;
import com.customertrace.repository.CreatorRepository;
import com.customertrace.repository.RestaurantCreatorAssignmentRepository;
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
    protected RestaurantCreatorAssignmentRepository assignmentRepository;

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

    /**
     * Signs in and returns the whole result, so the caller can reuse the issued cookies.
     */
    protected MvcResult loginFor(String userId) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","password":"%s","portal":"restaurant"}
                                """.formatted(userId, TEST_PASSWORD)))
                .andReturn();
    }

    /**
     * Posts to a cookie-authenticated route the way the browser does: the session cookie plus
     * the custom header and the echoed CSRF token.
     *
     * <p>{@code xsrfFrom} is separate from {@code sessionFrom} because a refresh rotates only the
     * session cookie; the CSRF cookie rides along unchanged until it is regenerated.
     */
    protected ResultActions postWithSessionCookies(MvcResult sessionFrom, MvcResult xsrfFrom,
                                                   String path) throws Exception {
        jakarta.servlet.http.Cookie session = sessionFrom.getResponse().getCookie("ct_refresh");
        jakarta.servlet.http.Cookie xsrf = xsrfFrom.getResponse().getCookie("XSRF-TOKEN");
        return mockMvc.perform(post(path)
                .cookie(session, xsrf)
                .header(SecurityConfig.CSRF_HEADER, "1")
                .header("X-XSRF-TOKEN", xsrf == null ? "" : xsrf.getValue()));
    }

    /** Posts using a single result for both cookies, as a fresh login would. */
    protected ResultActions postWithSessionCookies(MvcResult login, String path)
            throws Exception {
        return postWithSessionCookies(login, login, path);
    }

    /**
 * Creates a user with the given id and grants the role, then gives it a creator profile.
 *
 * <p>Separate from {@link #createCreator} so callers can choose the user id; the base helper
 * invents one from the display name.
 */
    protected Creator createCreatorWithId(String userId, String displayName, String handle) {
        User user = createUser(userId, "CREATOR", displayName);
        Creator creator = new Creator();
        creator.setUserId(user.getId());
        creator.setDisplayName(displayName);
        creator.setHandle(handle);
        creator.setCity("Kochi");
        creator.setFollowers(10_000);
        return creatorRepository.save(creator);
    }

    /**
     * Creates a creator with a caller-chosen user id, then assigns them to {@code restaurant}.
     *
     * <p>Campaign creation requires an active assignment, so fixtures that post campaigns need one
     * in place.
     */
    protected Creator createCreatorWithId(String userId, String displayName, String handle,
                                          Restaurant restaurant) {
        Creator creator = createCreatorWithId(userId, displayName, handle);
        assignCreator(creator, restaurant);
        return creator;
    }

    /**
     * Creates a creator and assigns them to {@code restaurant}.
     *
     * <p>An overload of {@link #createCreator} so a fixture that posts campaigns needs no separate
     * assignment step.
     */
    protected Creator createCreator(String userId, String displayName, String handle,
                                    Restaurant restaurant) {
        Creator creator = createCreator(userId, displayName, handle);
        assignCreator(creator, restaurant);
        return creator;
    }

    /**
     * Creates an active creator-to-restaurant assignment directly.
     *
     * <p>Campaign creation requires an active assignment, so any test that posts a campaign needs
     * one in place. Writing through the repository keeps the fixture independent of the admin
     * endpoint that other tests exercise directly.
     */
    protected void assignCreator(Creator creator, Restaurant restaurant) {
        RestaurantCreatorAssignment assignment = new RestaurantCreatorAssignment();
        assignment.setRestaurantId(restaurant.getId());
        assignment.setCreatorId(creator.getId());
        assignment.setActive(true);
        assignmentRepository.save(assignment);
    }

    /** Creates a restaurant and assigns {@code creator} to it. */
    protected Restaurant createRestaurant(String userId, String name, String city,
                                          Creator creator) {
        Restaurant restaurant = createRestaurant(userId, name, city);
        assignCreator(creator, restaurant);
        return restaurant;
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