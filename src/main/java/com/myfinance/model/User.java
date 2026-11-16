package com.myfinance.model;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class User {
    private static final int HASH_ITERATIONS = 210_000;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String login;
    private final byte[] salt;
    private final byte[] passwordHash;
    private Wallet wallet = new Wallet();

    public User(String login, String password) {
        if (password == null || password.length() < 8 || password.isBlank()) {
            throw new IllegalArgumentException("Пароль должен содержать минимум 8 символов и не состоять из пробелов.");
        }
        this.login = normalizeLogin(login);
        this.salt = new byte[16];
        RANDOM.nextBytes(salt);
        this.passwordHash = hash(password);
    }

    private User(String login, byte[] salt, byte[] passwordHash) {
        this.login = normalizeLogin(login);
        this.salt = salt.clone();
        this.passwordHash = passwordHash.clone();
    }


    public static User restore(String login, Credentials credentials) {
        if (credentials == null || !ALGORITHM.equals(credentials.algorithm())
                || credentials.iterations() != HASH_ITERATIONS) {
            throw new IllegalArgumentException("Неподдерживаемые параметры хеширования пароля.");
        }
        if (credentials.salt() == null || credentials.hash() == null) {
            throw new IllegalArgumentException("Не указаны соль или хеш пароля.");
        }
        byte[] salt = Base64.getDecoder().decode(credentials.salt());
        byte[] hash = Base64.getDecoder().decode(credentials.hash());
        if (salt.length != 16 || hash.length != 32) {
            throw new IllegalArgumentException("Некорректная длина соли или хеша.");
        }
        return new User(login, salt, hash);
    }

    public Credentials getCredentials() {
        return new Credentials(ALGORITHM, HASH_ITERATIONS,
                Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(passwordHash));
    }

    public record Credentials(String algorithm, int iterations, String salt, String hash) {
    }

    public static String normalizeLogin(String login) {
        if (login == null || login.isBlank()) {
            throw new IllegalArgumentException("Логин не должен быть пустым.");
        }
        return login.strip();
    }

    public String getLogin() {
        return login;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public void replaceWallet(Wallet wallet) {
        this.wallet = Objects.requireNonNull(wallet, "Кошелёк не должен быть null.");
    }

    public boolean matchesPassword(String password) {
        return password != null && MessageDigest.isEqual(passwordHash, hash(password));
    }

    private byte[] hash(String password) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось вычислить хеш пароля.", e);
        } finally {
            spec.clearPassword();
        }
    }
}
