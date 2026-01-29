package com.digitalgroup.holape.security;

import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.user.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

@Getter
public class CustomUserDetails implements UserDetails {

    private final Long id;
    private final String email;
    private final String password;
    private final String firstName;
    private final String lastName;
    private final String phone;
    private final Long clientId;
    private final String role;
    private final UserRole userRole;
    private final boolean active;
    private final Collection<? extends GrantedAuthority> authorities;

    public CustomUserDetails(User user) {
        this(user, user.getClient() != null ? user.getClient().getId() : null);
    }

    /**
     * Constructor with clientId override.
     * Used for Super Admin client switching functionality.
     * PARIDAD: Rails set_current_client action
     */
    public CustomUserDetails(User user, Long overrideClientId) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.password = user.getEncryptedPassword();
        this.firstName = user.getFirstName();
        this.lastName = user.getLastName();
        this.phone = user.getPhone();
        this.clientId = overrideClientId;
        this.role = user.getRole().name();
        this.userRole = user.getRole();
        this.active = user.getStatus() == Status.ACTIVE;
        this.authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_" + user.getRole().name())
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }

    // Role checking convenience methods
    public boolean isSuperAdmin() {
        return userRole == UserRole.SUPER_ADMIN;
    }

    public boolean isAdmin() {
        return userRole == UserRole.SUPER_ADMIN || userRole == UserRole.ADMIN;
    }

    public boolean isManager() {
        return userRole.isManager();
    }

    public boolean isManagerLevel1() {
        return userRole == UserRole.MANAGER_LEVEL_1;
    }

    public boolean isManagerLevel2() {
        return userRole == UserRole.MANAGER_LEVEL_2;
    }

    public boolean isManagerLevel3() {
        return userRole == UserRole.MANAGER_LEVEL_3;
    }

    public boolean isManagerLevel4() {
        return userRole == UserRole.MANAGER_LEVEL_4;
    }

    public boolean isAgent() {
        return userRole == UserRole.AGENT;
    }

    public boolean isStaff() {
        return userRole == UserRole.STAFF;
    }

    public boolean isInternal() {
        return userRole.isInternal();
    }

    public boolean canManageUsers() {
        return userRole.canManageUsers();
    }
}
