package com.customertrace.bootstrap;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.customertrace.domain.Creator;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.Role;
import com.customertrace.domain.User;
import com.customertrace.repository.CreatorRepository;
import com.customertrace.repository.RestaurantRepository;
import com.customertrace.repository.RoleRepository;
import com.customertrace.repository.UserRepository;
import com.customertrace.security.RoleCodes;

/**
 * Seeds reference data (roles) always, and development accounts only when explicitly enabled.
 *
 * <p><b>Safety properties.</b>
 * <ul>
 *   <li>Off unless {@code BOOTSTRAP_ENABLED=true}; a production deployment never seeds users.</li>
 *   <li>The password is read from the environment. With none configured, seeding is skipped
 *       rather than falling back to a guessable default.</li>
 *   <li>Idempotent: an existing account is left untouched, so restarts never duplicate rows or
 *       reset a password someone has since changed.</li>
 *   <li>Passwords are BCrypt-hashed before being persisted; account ids are logged but the
 *       password never is.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(BootstrapProperties.class)
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final RestaurantRepository restaurantRepository;
    private final CreatorRepository creatorRepository;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapProperties properties;

    public DataSeeder(RoleRepository roleRepository, UserRepository userRepository,
                      RestaurantRepository restaurantRepository,
                      CreatorRepository creatorRepository, PasswordEncoder passwordEncoder,
                      BootstrapProperties properties) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.restaurantRepository = restaurantRepository;
        this.creatorRepository = creatorRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedRoles();

        if (!properties.shouldSeed()) {
            log.info("Development account seeding is disabled (BOOTSTRAP_ENABLED={}).",
                    properties.isEnabled());
            return;
        }

        String hash = passwordEncoder.encode(properties.getDevAccountPassword());

        // One administrator, two restaurants and two creators, so the dual-approval and
        // ownership rules can be exercised locally.
        createUser("admin@Trynearby", "Platform Administrator", RoleCodes.ADMIN, hash, null, null);
        createUser("copper@restrnt", "Copper Chimney", RoleCodes.RESTAURANT, hash,
                "Copper Chimney", "Coimbatore");
        createUser("malabar@restrnt", "The Malabar Table", RoleCodes.RESTAURANT, hash,
                "The Malabar Table", "Palakkad");
        createUser("pooja@creator", "Pooja Ramachandran", RoleCodes.CREATOR, hash, null, null);
        createUser("aiswarya@creator", "Aiswarya Menon", RoleCodes.CREATOR, hash, null, null);

        ensureCreatorProfiles();
    }

    /** Roles are reference data and are always ensured, independent of the account flag. */
    private void seedRoles() {
        for (Role role : List.of(
                new Role(RoleCodes.ADMIN, "TryNearby Administrator",
                        "Platform governance and campaign approval"),
                new Role(RoleCodes.RESTAURANT, "Restaurant Partner",
                        "Restaurant dashboard, scans and redemptions"),
                new Role(RoleCodes.CREATOR, "Content Creator",
                        "Campaign creation and performance reports"))) {
            roleRepository.findByCode(role.getCode())
                    .orElseGet(() -> {
                        log.info("Creating missing role {}", role.getCode());
                        return roleRepository.save(role);
                    });
        }
    }
/**
     * Creates a user only when absent.
     *
     * <p>Never updates an existing account, so a restart cannot resurrect a disabled account or
     * overwrite a rotated password.
     */
    private void createUser(String userId, String displayName, String roleCode, String hash,
                            String restaurantName, String restaurantCity) {
        if (userRepository.existsByUserIdIgnoreCase(userId)) {
            return;
        }
        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new IllegalStateException("Role not seeded: " + roleCode));

        User user = new User();
        user.setUserId(userId);
        user.setDisplayName(displayName);
        user.setPasswordHash(hash);
        user.setStatus("ACTIVE");
        user.getRoles().add(role);
        User saved = userRepository.save(user);

        log.info("Seeded development account {}", userId);

        if (restaurantName != null) {
            Restaurant restaurant = new Restaurant();
            restaurant.setUserId(saved.getId());
            restaurant.setName(restaurantName);
            restaurant.setSlug(slugify(restaurantName));
            restaurant.setCity(restaurantCity);
            restaurant.setRegion(restaurantCity);
            restaurant.setAddress(restaurantCity + ", India");
            restaurantRepository.save(restaurant);
        }
    }

    /** Adds a minimal creator profile for each seeded creator account. */
    private void ensureCreatorProfiles() {
        for (String userId : List.of("pooja@creator", "aiswarya@creator")) {
            Optional<User> user = userRepository.findByUserIdIgnoreCase(userId);
            if (user.isEmpty()) {
                continue;
            }
            if (creatorRepository.findByUserId(user.get().getId()).isPresent()) {
                continue;
            }
            Creator creator = new Creator();
            creator.setUserId(user.get().getId());
            creator.setHandle(handleFor(user.get().getDisplayName()));
            creator.setDisplayName(user.get().getDisplayName());
            creator.setCity("Kochi");
            creator.setNiche("Food");
            creator.setFollowers(64_000);
            creator.setVerified(true);
            creatorRepository.save(creator);
        }
    }

    private String slugify(String value) {
        return value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private String handleFor(String displayName) {
        return "@" + displayName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}