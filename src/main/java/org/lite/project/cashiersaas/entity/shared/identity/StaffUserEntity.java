package org.lite.project.cashiersaas.entity.shared.identity;

import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.shared.StatusConstants;

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
        staffUser.status = StatusConstants.PENDING_PROFILE;
        return staffUser;
    }

    public static StaffUserEntity createCashier(String normalizedEmail, String passwordHash, String fullName) {
        StaffUserEntity staffUser = registerWithPassword(normalizedEmail, passwordHash, fullName);
        staffUser.status = StatusConstants.ACTIVE;
        return staffUser;
    }

    public boolean isActive() {
        return StatusConstants.ACTIVE.equals(status);
    }

    public boolean isPendingProfile() {
        return StatusConstants.PENDING_PROFILE.equals(status);
    }

    public boolean isDisabled() {
        return StatusConstants.DISABLED.equals(status);
    }

    public void completeBusinessProfile() {
        if (!isPendingProfile()) {
            throw new IllegalStateException("Business profile must be pending before it can be completed.");
        }
        status = StatusConstants.ACTIVE;
    }
}
