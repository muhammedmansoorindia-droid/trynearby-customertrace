package com.customertrace.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.customertrace.domain.CampaignStatusHistory;

public interface CampaignStatusHistoryRepository
        extends JpaRepository<CampaignStatusHistory, Long> {

    List<CampaignStatusHistory> findByCampaignIdOrderByChangedAtAsc(Long campaignId);
}