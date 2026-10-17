package com.wtc.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

    private static User user(Set<Role> roles, boolean mfa, Instant lockedUntil) {
        return new User(
                1,
                UUID.randomUUID(),
                "a@b.cd",
                "hash",
                "A",
                null,
                UserStatus.ACTIVE,
                roles,
                mfa,
                mfa,
                null,
                0,
                lockedUntil,
                null,
                null);
    }

    @Test
    void anyRoleOtherThanCustomerMakesAUserStaff() {
        assertThat(user(Set.of(Role.CUSTOMER), false, null).isStaff()).isFalse();
        for (Role role : new Role[] {Role.SUPPORT, Role.ADMIN, Role.SUPER_ADMIN}) {
            assertThat(user(Set.of(role), false, null).isStaff()).as(role.name()).isTrue();
        }
        assertThat(user(Set.of(Role.CUSTOMER, Role.SUPPORT), false, null).isStaff()).isTrue();
    }

    @Test
    void staffWithoutMfaMustEnrollButCustomersNeedNot() {
        assertThat(user(Set.of(Role.ADMIN), false, null).requiresMfaEnrollment()).isTrue();
        assertThat(user(Set.of(Role.ADMIN), true, null).requiresMfaEnrollment()).isFalse();
        assertThat(user(Set.of(Role.CUSTOMER), false, null).requiresMfaEnrollment()).isFalse();
    }

    @Test
    void lockIsActiveOnlyUntilItsExpiry() {
        assertThat(user(Set.of(Role.CUSTOMER), false, null).isLocked(NOW)).isFalse();
        assertThat(user(Set.of(Role.CUSTOMER), false, NOW.plusSeconds(1)).isLocked(NOW)).isTrue();
        assertThat(user(Set.of(Role.CUSTOMER), false, NOW).isLocked(NOW)).isFalse();
        assertThat(user(Set.of(Role.CUSTOMER), false, NOW.minusSeconds(1)).isLocked(NOW)).isFalse();
    }

    @Test
    void rolesAreDefensivelyCopied() {
        var mutable = new java.util.HashSet<>(Set.of(Role.CUSTOMER));
        User user = user(mutable, false, null);
        mutable.add(Role.SUPER_ADMIN);
        assertThat(user.roles()).containsExactly(Role.CUSTOMER);
    }
}
