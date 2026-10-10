package com.mrlbs.waitlist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {
    long countByResource_Id(Long resourceId);
    List<WaitlistEntry> findAllByResource_Id(Long resourceId);
}
