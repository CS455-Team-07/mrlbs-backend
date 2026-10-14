package com.mrlbs.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InAppNotificationRepository extends JpaRepository<InAppNotification, UUID> {

    List<InAppNotification> findByMemberIdOrderByCreatedAtDesc(UUID memberId);

    List<InAppNotification> findByMemberIdAndIsReadFalseOrderByCreatedAtDesc(UUID memberId);

    long countByMemberIdAndIsReadFalse(UUID memberId);
}
