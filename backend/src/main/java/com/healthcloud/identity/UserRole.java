package com.healthcloud.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/** A role granted to a specific organization membership (roles are organization-scoped). */
@Entity
@Table(name = "user_role")
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_membership_id", nullable = false)
    private OrganizationMembership membership;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    protected UserRole() {
        // for JPA
    }

    public UserRole(OrganizationMembership membership, Role role) {
        this.membership = membership;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public OrganizationMembership getMembership() {
        return membership;
    }

    public Role getRole() {
        return role;
    }
}
