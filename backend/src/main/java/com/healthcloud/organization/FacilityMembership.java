package com.healthcloud.organization;

import com.healthcloud.identity.OrganizationMembership;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Associates a staff member (an organization membership) with a facility. */
@Entity
@Table(name = "facility_membership")
public class FacilityMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", nullable = false)
    private Facility facility;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_membership_id", nullable = false)
    private OrganizationMembership membership;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected FacilityMembership() {
        // for JPA
    }

    public FacilityMembership(Facility facility, OrganizationMembership membership) {
        this.facility = facility;
        this.membership = membership;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public Facility getFacility() {
        return facility;
    }

    public OrganizationMembership getMembership() {
        return membership;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
