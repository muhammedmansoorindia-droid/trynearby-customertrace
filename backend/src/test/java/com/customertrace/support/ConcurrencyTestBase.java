package com.customertrace.support;

import java.util.LinkedHashSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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

/**
 * Base class for concurrency tests.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> A rolled-back test transaction would hide
 * the fixtures from the worker threads, so every test here would pass for the wrong reason —
 * the threads would be racing over data they cannot see. Fixtures are committed instead and
 * removed explicitly in {@link #tearDownWorld()}.
 *
 * <p>Account ids carry a per-run suffix so repeated runs never collide, and cleanup is scoped to
 * exactly those ids.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ConcurrencyTestBase {

    protected static final String TEST_PASSWORD = "TestPassw0rd!";

    /** Unique per run, so fixtures never collide with a previous run. */
    protected final String runId = UUID.randomUUID().toString().substring(0, 8);

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
    protected JdbcTemplate jdbc;

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

    protected String tokenFor(User user) {
        return jwtService.createAccessToken(user.getId(), user.getUserId(),
                new LinkedHashSet<>(user.getRoleCodes()).stream().sorted().toList());
    }

    protected MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder,
                                                     String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    protected JsonNode parse(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** The persisted promo code for a campaign, or null when it has none. */
    protected String promoCodeFor(long campaignId) {
        return jdbc.queryForObject(
                "SELECT code FROM promo_codes WHERE campaign_id = ?", String.class, campaignId);
    }

    /** Counts persisted redemptions for a campaign. */
    protected int redemptionCount(long campaignId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM redemptions WHERE campaign_id = ?", Integer.class, campaignId);
        return count == null ? 0 : count;
    }

    /**
     * Removes this run's fixtures.
 *
     * <p>Several audit-bearing columns are {@code ON DELETE RESTRICT} rather than cascade —
     * {@code campaign_decisions.decided_by} and {@code redemptions.redeemed_by} must keep
     * pointing at a real account, which is correct for production data. Test fixtures therefore
     * have to remove those children explicitly before deleting the accounts.
     */
    protected void tearDownWorld() {
        String like = "%" + runId + "%";
        jdbc.update("DELETE FROM campaign_decisions WHERE decided_by IN "
                + "(SELECT id FROM users WHERE user_id LIKE ?)", like);
        jdbc.update("DELETE FROM redemptions WHERE redeemed_by IN "
                + "(SELECT id FROM users WHERE user_id LIKE ?)", like);
        // Everything else cascades from users / campaigns / restaurants / creators.
        jdbc.update("DELETE FROM users WHERE user_id LIKE ?", like);
    }

    private String slug(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
