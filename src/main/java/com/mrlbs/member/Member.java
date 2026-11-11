package com.mrlbs.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "members")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "credential_version")
    private UUID credentialVersion;

    protected Member() {
    }

    public Member(UUID id, String email, String name, String role, LocalDateTime createdAt) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.role = role != null ? role : "MEMBER";
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
    }

    public Member(String email, String name, String role) {
        this(null, email, name, role, LocalDateTime.now());
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UUID getCredentialVersion() {
        return credentialVersion;
    }

    public void replacePassword(String hash) {
        this.passwordHash = hash;
        this.credentialVersion = UUID.randomUUID();
    }
}
