package com.myfinance.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CredentialsTest {
    @Test
    void restoreKeepsSaltAndHashWithoutKnowingPlainPassword() {
        User registered = new User("alice", "password1");
        User.Credentials credentials = registered.getCredentials();
        User restored = User.restore("alice", credentials);
        assertEquals(credentials, restored.getCredentials());
        assertTrue(restored.matchesPassword("password1"));
        assertFalse(restored.matchesPassword("wrongpass"));
        assertNotEquals(credentials.salt(), new User("bob", "password1").getCredentials().salt());
    }

    @Test
    void malformedCredentialsAreRejected() {
        User.Credentials valid = new User("alice", "password1").getCredentials();
        assertThrows(IllegalArgumentException.class, () -> User.restore("alice", null));
        assertThrows(IllegalArgumentException.class, () -> User.restore("alice",
                new User.Credentials("SHA-256", valid.iterations(), valid.salt(), valid.hash())));
        assertThrows(IllegalArgumentException.class, () -> User.restore("alice",
                new User.Credentials(valid.algorithm(), 1, valid.salt(), valid.hash())));
        assertThrows(IllegalArgumentException.class, () -> User.restore("alice",
                new User.Credentials(valid.algorithm(), valid.iterations(), "bad base64", valid.hash())));
        assertThrows(IllegalArgumentException.class, () -> User.restore("alice",
                new User.Credentials(valid.algorithm(), valid.iterations(), "AA==", valid.hash())));
        assertThrows(IllegalArgumentException.class, () -> User.restore("alice",
                new User.Credentials(valid.algorithm(), valid.iterations(), null, valid.hash())));
    }
}
