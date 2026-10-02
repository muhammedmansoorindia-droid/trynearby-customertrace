package com.customertrace.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.customertrace.domain.PromoTierLedger;

/**
 * Tier ledger persistence.
 *
 * <p>{@code existsByRestaurantIdAndCalendarMonthAndTierLetter} backs the E-J "once per restaurant
 * per calendar month" rule. The matching unique constraint in the schema turns a concurrent
 * double-allocation into a constraint violation rather than a duplicate grant.
 */
public interface PromoTierLedgerRepository extends JpaRepository<PromoTierLedger, Long> {

    boolean existsByRestaurantIdAndCalendarMonthAndTierLetter(Long restaurantId, String calendarMonth,
                                                               String tierLetter);
}