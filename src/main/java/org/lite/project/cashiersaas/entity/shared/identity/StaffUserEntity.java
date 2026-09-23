package org.lite.project.cashiersaas.entity.shared.identity;

import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "staff_users")
@NoArgsConstructor
public class StaffUserEntity extends MutableBaseEntity {
    @Column(nullable = false, unique = true, length = 320)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;
    @Column(nullable = false, length = 16)
    private String status;

    public static StaffUserEntity registerWithPassword(String normalizedEmail, String passwordHash, String fullName) {
        StaffUserEntity staffUser = new StaffUserEntity();
        staffUser.email = normalizedEmail;
        staffUser.passwordHash = passwordHash;
        staffUser.fullName = fullName;
        staffUser.status = "PENDING_PROFILE";
        return staffUser;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean isPendingProfile() {
        return "PENDING_PROFILE".equals(status);
    }

    public boolean isDisabled() {
        return "DISABLED".equals(status);
    }

    public void completeBusinessProfile() {
        if (!isPendingProfile()) {
            throw new IllegalStateException("Business profile must be pending before it can be completed.");
        }
        status = "ACTIVE";
    }
}
