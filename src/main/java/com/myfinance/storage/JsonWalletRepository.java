package com.myfinance.storage;

import com.myfinance.model.Category;
import com.myfinance.model.Expense;
import com.myfinance.model.Income;
import com.myfinance.model.User;
import com.myfinance.model.Wallet;
import com.myfinance.repository.WalletRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

public final class JsonWalletRepository implements WalletRepository {
    private final Path walletDirectory;
    private final Path exportDirectory;

    public JsonWalletRepository(Path dataDirectory, Path exportDirectory) {
        this.walletDirectory = dataDirectory.resolve("wallets");
        this.exportDirectory = exportDirectory;
    }

    @Override
    public Wallet load(String login) throws IOException {
        Path file = walletFile(login);
        return ExportFiles.exists(file) ? read(file, login) : new Wallet();
    }

    @Override
    public void save(String login, Wallet wallet) throws IOException {
        Path file = walletFile(login);
        if (ExportFiles.exists(file)) {
            read(file, login);
        }
        write(file, login, wallet);
    }

    @Override
    public Wallet readSnapshot(String login, String fileName) throws IOException {
        return read(ExportFiles.resolve(exportDirectory, fileName, ".json"), login);
    }

    @Override
    public void writeSnapshot(String login, Wallet wallet, String fileName) throws IOException {
        write(ExportFiles.resolve(exportDirectory, fileName, ".json"), login, wallet);
    }

    private Path walletFile(String login) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(User.normalizeLogin(login).getBytes(StandardCharsets.UTF_8));
            return ExportFiles.resolve(walletDirectory, HexFormat.of().formatHex(digest) + ".json", ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен.", e);
        }
    }

    private void write(Path file, String login, Wallet wallet) throws IOException {
        List<CategoryData> categories = wallet.getCategories().stream()
                .map(category -> new CategoryData(category.getName(), category.getType().name())).toList();
        List<TransactionData> transactions = wallet.getTransactions().stream()
                .map(transaction -> new TransactionData(transaction.getCategory().getType().name(),
                        transaction.getCategory().getName(), transaction.getAmount().toPlainString(),
                        transaction.getDate().toString())).toList();
        List<BudgetData> budgets = wallet.getAllBudgetStatuses().stream()
                .map(status -> new BudgetData(status.category().getName(), status.month().toString(),
                        status.limit().toPlainString())).toList();
        JsonFiles.write(file, new WalletData(1, User.normalizeLogin(login), categories, transactions, budgets));
    }

    private Wallet read(Path file, String login) throws IOException {
        WalletData data = JsonFiles.read(file, WalletData.class);
        try {
            if (data.schemaVersion() != 1) {
                throw new IllegalArgumentException("Неподдерживаемая версия снимка кошелька.");
            }
            if (!User.normalizeLogin(login).equals(data.owner())) {
                throw new IllegalArgumentException("Снимок принадлежит другому пользователю.");
            }
            Wallet wallet = new Wallet();
            for (CategoryData category : data.categories()) {
                requireItem(category);
                wallet.createCategory(category.name(), Category.Type.valueOf(category.type()));
            }
            for (TransactionData transaction : data.transactions()) {
                requireItem(transaction);
                Category category = wallet.getCategory(transaction.categoryName());
                BigDecimal amount = new BigDecimal(transaction.amount());
                LocalDate date = LocalDate.parse(transaction.date());
                Category.Type type = Category.Type.valueOf(transaction.type());
                wallet.addTransaction(type == Category.Type.INCOME
                        ? new Income(amount, date, category) : new Expense(amount, date, category));
            }
            Set<BudgetKey> keys = new HashSet<>();
            for (BudgetData budget : data.budgets()) {
                requireItem(budget);
                Category category = wallet.getCategory(budget.categoryName());
                YearMonth month = YearMonth.parse(budget.month());
                if (!keys.add(new BudgetKey(category, month))) {
                    throw new IllegalArgumentException("Повторяющийся бюджет в JSON.");
                }
                wallet.setBudget(category.getName(), month, new BigDecimal(budget.limit()));
            }
            return wallet;
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IOException("Некорректный снимок «" + file + "»: " + e.getMessage(), e);
        }
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new IllegalArgumentException("Список JSON содержит пустую запись.");
        }
    }

    private record BudgetKey(Category category, YearMonth month) {
    }

    public record WalletData(int schemaVersion, String owner, List<CategoryData> categories,
                             List<TransactionData> transactions, List<BudgetData> budgets) {
    }

    public record CategoryData(String name, String type) {
    }

    public record TransactionData(String type, String categoryName, String amount, String date) {
    }

    public record BudgetData(String categoryName, String month, String limit) {
    }
}
