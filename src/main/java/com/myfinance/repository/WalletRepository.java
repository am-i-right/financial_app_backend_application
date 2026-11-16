package com.myfinance.repository;

import com.myfinance.model.Wallet;
import java.io.IOException;

public interface WalletRepository {
    Wallet load(String login) throws IOException;

    void save(String login, Wallet wallet) throws IOException;

    Wallet readSnapshot(String login, String fileName) throws IOException;

    void writeSnapshot(String login, Wallet wallet, String fileName) throws IOException;
}
