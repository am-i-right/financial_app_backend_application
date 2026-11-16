package com.myfinance.repository;

import com.myfinance.model.User;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

public interface UserRepository {
    List<User> load() throws IOException;

    void save(Collection<User> users) throws IOException;
}
