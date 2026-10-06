package com.customertrace.service.promo;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

/**
 * Generates, parses and validates promo codes in the {@code LLNNDDX} format.
 *
 * <pre>
 *   positions 1-2  LL   two random uppercase letters
 *   positions 3-4  NN   customer target, 01-99 (two digits)
 *   positions 5-6  DD   campaign duration in days, 01-99 (two digits)
 *   position  7    X    discount letter B-J, where B=2% … J=10%
 * </pre>
 *
 * <p>Monthly rule: B-F (2%-6%) may be reused freely. G-J (7%-10%) may back at most one campaign
 * per restaurant per calendar month, enforced by {@link CampaignWorkflowService} through the
 * {@code promo_tier_ledger} unique key so two concurrent submissions cannot both claim a letter.
 *
 * <p>Pure and stateless apart from the injected code probe, so every rule is directly
 * unit-testable.
 */
@Component
public class PromoCodeGenerator {

    /**
     * Two letters A-Z, 01-99, 01-99, discount letter B-J.
     *
     * <p>The target and duration groups each allow a leading zero. The generator's own bounds
     * reject zero, but a code read back from the database is parsed with this pattern, which only
     * has to recognise the shape; range checks happen in {@link #parse}.
     */
    public static final String PROMO_CODE_PATTERN = "^[A-Z]{2}[0-9]{2}[0-9]{2}[B-J]$";

    public static final int MIN_TARGET = 1;
    public static final int MAX_TARGET = 99;
    public static final int MIN_DURATION = 1;
    public static final int MAX_DURATION = 99;

    /**
     * B-F (2%-6%): reusable any number of times per restaurant per month.
     *
     * <p>Held as a range rather than an array so {@link DiscountTiers} stays the single source of
     * truth for which letters mean what.
     */
    private static final char REUSABLE_LAST = 'F';
    /** G-J (7%-10%): at most one campaign per restaurant per calendar month. */
    private static final char RESTRICTED_FIRST = 'G';

    private static final int MAX_ATTEMPTS = 50;

    /**
     * Probes, given a candidate code, whether it already exists.
     *
     * <p>A {@code Function} rather than a {@code BooleanSupplier} because the probe needs the
     * candidate value. Left null, every candidate is considered available, which keeps the
     * generator usable as a pure unit under test.
     */
    private java.util.function.Function<String, Boolean> codeExistsPredicate;

    public PromoCodeGenerator withCodeExistsChecker(
            java.util.function.Function<String, Boolean> checker) {
        this.codeExistsPredicate = checker;
        return this;
    }

    /** True when the candidate code is already taken. */
    private boolean codeTaken(String code) {
        return codeExistsPredicate != null && Boolean.TRUE.equals(codeExistsPredicate.apply(code));
    }


    /**
     * Validates that the requested values can actually be represented in two digits.
     *
     * <p>Called before generation so an unrepresentable request is rejected with a clear
     * message instead of being silently truncated (150 customers cannot fit in two digits and
     * would otherwise become "15").
     *
     * <p>Zero is rejected for both fields: a campaign that targets nobody, or that lasts no days,
     * is not a campaign. The upper bound is 99 because that is all two digits can carry.
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
     * Generates a unique promo code for a given discount.
     *
     * <p>The discount letter is <em>derived</em> from {@code discountPercent}, never chosen at
     * random: a 5% campaign must end in E, or the code would advertise one discount and settle
     * another. Only the two leading letters are random, and they are re-drawn until the whole code
     * is unused.
     *
     * @param customerTarget  customer cap, 1-99, encoded as NN
     * @param durationDays    campaign duration, 1-99 days, encoded as DD
     * @param discountPercent 2-10, encoded as the discount letter B-J
     * @return a code matching {@code LLNNDDX}
     * @throws PromoCodeValidationException when the values are not encodable, or no unique code
     *                                      could be found within the attempt budget
     */
    public String generate(int customerTarget, int durationDays, int discountPercent) {
        validateEncodable(customerTarget, durationDays);

        String target = String.format("%02d", customerTarget);
        String duration = String.format("%02d", durationDays);
        String tier = String.valueOf(DiscountTiers.letterFor(discountPercent));

        // The NN/DD/discount segments are fixed by the request, so the letters are the only free
        // part: only they are re-drawn on a collision, and the requested values never change.
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = randomLetters() + target + duration + tier;
            if (!codeTaken(code)) {
                return code;
            }
        }
        throw new PromoCodeValidationException(
                "Could not generate a unique promo code after " + MAX_ATTEMPTS
                        + " attempts. Please retry.");
    }

    /**
     * A campaign name, derived from the campaign's own settings.
     *
     * <p>Every campaign is named by the same rule that mints its promo code, so the two can never
     * disagree and there is nothing for a creator to mistype: two random letters, the customer
     * target, the duration, and the discount letter. A creator never types a name.
     *
     * <p>The letters are the only random part. Regenerating a code therefore only re-draws them —
     * the target, duration and discount the creator chose are carried through untouched.
     */
    public String preview(int customerTarget, int durationDays, int discountPercent,
                          java.util.function.IntUnaryOperator letterSource) {
        validateEncodable(customerTarget, durationDays);
        return lettersFrom(letterSource) + String.format("%02d", customerTarget)
                + String.format("%02d", durationDays)
                + DiscountTiers.letterFor(discountPercent);
    }

    private String lettersFrom(java.util.function.IntUnaryOperator letterSource) {
        if (letterSource == null) {
            return randomLetters();
        }
        // Two draws, both mapped into A-Z, so a caller can supply its own preview letters.
        return "" + (char) ('A' + Math.floorMod(letterSource.applyAsInt(0), 26))
                + (char) ('A' + Math.floorMod(letterSource.applyAsInt(1), 26));
    }

    private String randomLetters() {
        StringBuilder sb = new StringBuilder(2);
        for (int i = 0; i < 2; i++) {
            sb.append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
        }
        return sb.toString();
    }

    /**
     * Whether the discount letter for a campaign may be issued again.
     *
     * <p>Advisory only: the authoritative check is the insert into {@code promo_tier_ledger},
     * whose unique key makes two concurrent campaigns fail rather than both succeeding.
     */
    public boolean isRestrictedDiscount(char letter) {
        return DiscountTiers.isRestricted(letter);
    }

    /** True when the letter's discount may be reused freely (B-F, 2%-6%). */
    public boolean isReusableDiscount(char letter) {
        return DiscountTiers.isReusable(letter);
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
}