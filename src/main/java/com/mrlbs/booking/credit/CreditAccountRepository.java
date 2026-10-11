package com.mrlbs.booking.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface CreditAccountRepository extends JpaRepository<CreditAccount, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from CreditAccount account where account.memberEmail = :email")
    Optional<CreditAccount> findForUpdate(@Param("email") String email);
}
