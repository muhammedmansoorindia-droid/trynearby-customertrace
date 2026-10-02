package com.customertrace.service.promo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import org.springframework.stereotype.Component;

/**
 * Generates, parses and validates promo codes in the {@code LLNNDDX} format.
 *
 * <pre>
 *   positions 1-2  LL   two random uppercase letters
 *   positions 3-4  NN   customer target, 10-99 (two digits)
 *   positions 5-6  DD   campaign duration in days, 01-31 (two digits)
 *   position  7    X    tier letter A-J
 * </pre>
 *
 * <p>Monthly rule: A-D tiers may be reused freely. E-J may be issued at most once per
 * restaurant per calendar month, tracked by the caller through a uniqueness probe (backed by the
 * {@code promo_tier_ledger} unique key) so two concurrent requests cannot claim the same tier.
 *
 * <p>Pure and stateless apart from the injected probes, so every rule is directly unit-testable.
 */
@Component
public class PromoCodeGenerator {

    /** Two letters, 10-99, 01-31, tier A-J. */
    public static final String PROMO_CODE_PATTERN = "^[A-Z]{2}[1-9][0-9](0[1-9]|[12][0-9]|3[01])[A-J]$";

    public static final int MIN_TARGET = 10;
    public static final int MAX_TARGET = 99;
    public static final int MIN_DURATION = 1;
    public static final int MAX_DURATION = 31;

    /** Tiers that may be reused an unlimited number of times. */
    public static final char[] REUSABLE_TIERS = {'A', 'B', 'C', 'D'};
    /** Tiers limited to one use per restaurant per calendar month. */
    public static final char[] EXCLUSIVE_TIERS = {'E', 'F', 'G', 'H', 'I', 'J'};

    private static final int MAX_ATTEMPTS = 50;

    /** Injected by the service layer: true when this code already exists. */
    private BooleanSupplier codeExistsChecker = () -> false;

    /** Injected by the service layer: true when an E-J tier is spent for restaurant+month. */
    private BooleanSupplier exclusiveTierTakenChecker = () -> false;

    public PromoCodeGenerator withCodeExistsChecker(BooleanSupplier checker) {
        this.codeExistsChecker = checker;
        return this;
    }

    public PromoCodeGenerator withExclusiveTierTakenChecker(BooleanSupplier checker) {
        this.exclusiveTierTakenChecker = checker;
        return this;
    }

    /**
     * Validates that the requested values can actually be represented in two digits.
     *
     * <p>Called before generation so an unrepresentable request is rejected with a clear
     * message instead of being silently truncated (150 customers cannot fit in two digits and
     * would otherwise become "15").
     *
     * @throws PromoCodeValidationException when the target or duration cannot be encoded
     */
    public void validateEncodable(int customerTarget, int durationDays) {
        if (customerTarget < MIN_TARGET || customerTarget > MAX_TARGET) {
            throw new PromoCodeValidationException(
                    "Customer target must be between " + MIN_TARGET + " and " + MAX_TARGET
                            + " because it is encoded as two digits (NN). Received: "
                            + customerTarget + ".");
        }
        if (durationDays < MIN_DURATION || durationDays > MAX_DURATION) {
            throw new PromoCodeValidationException(
                    "Campaign duration must be between " + MIN_DURATION + " and " + MAX_DURATION
                            + " days because it is encoded as two digits (DD). Received: "
                            + durationDays + ".");
        }
    }
/**
     * Generates a unique promo code.
     *
     * @param customerTarget customer cap, 10-99
     * @param durationDays   campaign duration, 1-31
     * @return a code matching {@code LLNNDDX}
     * @throws PromoCodeValidationException when the inputs are not encodable, or no unique code
     *                                      could be found within the attempt budget
     */
    public String generate(int customerTarget, int durationDays) {
        validateEncodable(customerTarget, durationDays);

        String target = String.format("%02d", customerTarget);
        String duration = String.format("%02d", durationDays);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            char tier = pickTier();

            for (int inner = 0; inner < MAX_ATTEMPTS; inner++) {
                String code = randomLetters() + target + duration + tier;
                if (!codeExistsChecker.getAsBoolean()) {
                    return code;
                }
            }
        }
        throw new PromoCodeValidationException(
                "Could not generate a unique promo code after " + MAX_ATTEMPTS
                        + " attempts. Please retry.");
    }

    /**
     * Chooses a tier letter.
     *
     * <p>Exclusive tiers are preferred because they are scarce; once all six are spent for this
     * restaurant and calendar month, a reusable A-D tier is used so campaigns can still be
     * created rather than failing outright.
     */
    private char pickTier() {
        // A char[] has no stream(); iterate so each tier can be probed individually.
        List<Character> unusedExclusive = new ArrayList<>();
        for (char tier : EXCLUSIVE_TIERS) {
            if (!exclusiveTierTakenChecker.getAsBoolean()) {
                unusedExclusive.add(tier);
            }
        }
        if (!unusedExclusive.isEmpty()) {
            return unusedExclusive.get(ThreadLocalRandom.current().nextInt(unusedExclusive.size()));
        }
        return REUSABLE_TIERS[ThreadLocalRandom.current().nextInt(REUSABLE_TIERS.length)];
    }

    private String randomLetters() {
        StringBuilder sb = new StringBuilder(2);
        for (int i = 0; i < 2; i++) {
            sb.append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
        }
        return sb.toString();
    }

    /**
     * Parses and range-checks a code.
     *
     * @return the parsed parts, or empty when the code does not match the format or carries an
     *         out-of-range target/duration
     */
    public Optional<ParsedPromoCode> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (code.length() != 7 || !code.matches(PROMO_CODE_PATTERN)) {
            return Optional.empty();
        }
        String letters = code.substring(0, 2);
        int target = Integer.parseInt(code.substring(2, 4));
        int duration = Integer.parseInt(code.substring(4, 6));
        char tier = code.charAt(6);

        if (target < MIN_TARGET || target > MAX_TARGET) {
            return Optional.empty();
        }
        if (duration < MIN_DURATION || duration > MAX_DURATION) {
            return Optional.empty();
        }
        return Optional.of(new ParsedPromoCode(letters, target, duration, tier));
    }

    /** True when the string matches the {@code LLNNDDX} shape exactly. */
    public boolean isValidFormat(String raw) {
        return raw != null && raw.trim().toUpperCase(Locale.ROOT).matches(PROMO_CODE_PATTERN);
    }

    /** True when the tier letter is in the exclusive E-J range. */
    public boolean isExclusiveTier(char tier) {
        return tier >= 'E' && tier <= 'J';
    }
}