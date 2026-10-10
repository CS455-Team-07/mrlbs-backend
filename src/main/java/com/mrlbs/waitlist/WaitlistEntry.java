package com.mrlbs.waitlist;

import jakarta.persistence.*;
import com.mrlbs.resource.Resource;

@Entity
@Table(name = "waitlist")
public class WaitlistEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @ManyToOne
    @JoinColumn(name = "resource_id", nullable = false)
    private Resource resource;

    public WaitlistEntry() {}

    public WaitlistEntry(Long memberId, Resource resource) {
        this.memberId = memberId;
        this.resource = resource;
    }

    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Resource getResource() { return resource; }
    public void setResource(Resource resource) { this.resource = resource; }
}
