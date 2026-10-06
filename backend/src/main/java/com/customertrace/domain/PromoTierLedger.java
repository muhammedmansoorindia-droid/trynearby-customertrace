package com.customertrace.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Ledger of restricted discount letters issued per restaurant per calendar month.
 *
 * <p>Holds the G-J (7%-10%) discounts a restaurant has already committed to in a given month.
 * The unique key on {@code (restaurant_id, calendar_month, tier_letter)} is what makes the
 * restriction safe under concurrency: two simultaneous campaign submissions that pick the same
 * scarce discount cannot both insert, so the second fails on the key rather than silently
 * granting a duplicate.
 *
 * <p>Because the key omits {@code creator_id}, the cap is a property of the venue: several
 * creators assigned to one restaurant share a single allowance for each high discount.
 */
@Entity
@Table(name = "promo_tier_ledger")
public class PromoTierLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    /** {@code YYYY-MM}, derived from the campaign start date (a DATE, so timezone-free). */
    @Column(name = "calendar_month", nullable = false, columnDefinition = "CHAR(7)")
    private String calendarMonth;

    @Column(name = "tier_letter", nullable = false, columnDefinition = "CHAR(1)")
    private String tierLetter;

    @Column(name = "promo_code_id")
    private Long promoCodeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRestaurantId() {
        return restaurantId;
    }

    public void setRestaurantId(Long restaurantId) {
        this.restaurantId = restaurantId;
    }

    public String getCalendarMonth() {
        return calendarMonth;
    }

    public void setCalendarMonth(String calendarMonth) {
        this.calendarMonth = calendarMonth;
    }

    public String getTierLetter() {
        return tierLetter;
    }

    public void setTierLetter(String tierLetter) {
        this.tierLetter = tierLetter;
    }

    public Long getPromoCodeId() {
        return promoCodeId;
    }

    public void setPromoCodeId(Long promoCodeId) {
        this.promoCodeId = promoCodeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}