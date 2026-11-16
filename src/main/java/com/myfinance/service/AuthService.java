package com.myfinance.service;

import com.myfinance.model.User;
import com.myfinance.model.Wallet;
import com.myfinance.repository.UserRepository;
import com.myfinance.repository.WalletRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;


public final class AuthService {
    private final Map<String, User> users = new LinkedHashMap<>();
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private User currentUser;

    public AuthService() {
        userRepository = null;
        walletRepository = null;
    }

    public AuthService(UserRepository userRepository, WalletRepository walletRepository) throws IOException {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.walletRepository = Objects.requireNonNull(walletRepository);
        for (User user : userRepository.load()) {
            if (users.putIfAbsent(user.getLogin(), user) != null) {
                throw new IOException("Повторяющийся логин в хранилище.");
            }
        }
    }

    public void register(String login, String password) {
        String normalizedLogin = User.normalizeLogin(login);
        if (users.containsKey(normalizedLogin)) {
            throw new IllegalArgumentException("Этот логин уже занят.");
        }
        users.put(normalizedLogin, new User(normalizedLogin, password));
        if (isPersistent()) {
            try {
                userRepository.save(users.values());
            } catch (IOException e) {
                users.remove(normalizedLogin);
                throw new UncheckedIOException("Не удалось сохранить регистрацию: " + e.getMessage(), e);
            }
        }
    }

    public void login(String login, String password) {
        if (currentUser != null) {
            throw new IllegalStateException("Сначала выйдите из текущей учётной записи.");
        }
        User user = users.get(User.normalizeLogin(login));
        if (user == null || !user.matchesPassword(password)) {
            throw new IllegalArgumentException("Неверный логин или пароль.");
        }
        if (isPersistent()) {
            try {
                Wallet restored = walletRepository.load(user.getLogin());
                user.replaceWallet(restored);
            } catch (IOException e) {
                throw new UncheckedIOException("Не удалось восстановить кошелёк: " + e.getMessage(), e);
            }
        }
        currentUser = user;
    }

    public void logout() {
        saveAll();
        currentUser = null;
    }

    public void saveAll() {
        if (isPersistent() && currentUser != null) {
            try {
                walletRepository.save(currentUser.getLogin(), currentUser.getWallet());
            } catch (IOException e) {
                throw new UncheckedIOException("Не удалось сохранить кошелёк: " + e.getMessage(), e);
            }
        }
    }

    public boolean isPersistent() {
        return userRepository != null;
    }

    public boolean isLoggedIn() {
        return currentUser != null;
    }

    public User getCurrentUser() {
        if (currentUser == null) {
            throw new IllegalStateException("Сначала войдите в учётную запись.");
        }
        return currentUser;
    }

}
