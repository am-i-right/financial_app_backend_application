package com.myfinance.storage;

import com.myfinance.model.User;
import com.myfinance.repository.UserRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class JsonUserRepository implements UserRepository {
    private final Path file;

    public JsonUserRepository(Path dataDirectory) throws IOException {
        file = ExportFiles.resolve(dataDirectory, "users.json", ".json");
    }

    @Override
    public List<User> load() throws IOException {
        if (!ExportFiles.exists(file)) {
            return List.of();
        }
        UsersData data = JsonFiles.read(file, UsersData.class);
        try {
            if (data.schemaVersion() != 1) {
                throw new IllegalArgumentException("Неподдерживаемая версия файла пользователей.");
            }
            List<User> users = new ArrayList<>();
            Set<String> logins = new HashSet<>();
            for (UserData item : data.users()) {
                if (item == null) {
                    throw new IllegalArgumentException("Пустая запись пользователя.");
                }
                User user = User.restore(item.login(), item.credentials());
                if (!logins.add(user.getLogin())) {
                    throw new IllegalArgumentException("Повторяющийся логин в файле пользователей.");
                }
                users.add(user);
            }
            return List.copyOf(users);
        } catch (IllegalArgumentException e) {
            throw new IOException("Некорректные данные «" + file + "»: " + e.getMessage(), e);
        }
    }

    @Override
    public void save(Collection<User> users) throws IOException {
        if (ExportFiles.exists(file)) {
            load();
        }
        List<UserData> entries = users.stream()
                .map(user -> new UserData(user.getLogin(), user.getCredentials())).toList();
        JsonFiles.write(file, new UsersData(1, entries));
    }

    public record UsersData(int schemaVersion, List<UserData> users) {
    }

    public record UserData(String login, User.Credentials credentials) {
    }
}
