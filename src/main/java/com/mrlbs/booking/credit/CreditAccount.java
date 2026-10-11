package com.mrlbs.booking.credit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

@Entity
@Table(name = "credit_accounts")
public class CreditAccount {
    @Id
    @Column(name = "member_email", nullable = false, length = 320)
    private String memberEmail;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal balance;

    @Version
    private long version;

    protected CreditAccount() {
    }

    public CreditAccount(String memberEmail, BigDecimal balance) {
        this.memberEmail = memberEmail;
        this.balance = balance;
    }

    public String getMemberEmail() { return memberEmail; }
    public BigDecimal getBalance() { return balance; }
    public void debit(BigDecimal amount) { this.balance = this.balance.subtract(amount); }
    public void credit(BigDecimal amount) { this.balance = this.balance.add(amount); }
}
