package com.customertrace.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.customertrace.domain.BookingStatusHistory;

public interface BookingStatusHistoryRepository extends JpaRepository<BookingStatusHistory, Long> {

    List<BookingStatusHistory> findByBookingIdOrderByChangedAtAsc(Long bookingId);
}
