package com.myfinance.service;

import com.myfinance.model.Category;
import com.myfinance.model.Expense;
import com.myfinance.model.Income;
import com.myfinance.model.Transaction;
import com.myfinance.model.User;
import com.myfinance.model.Wallet;
import com.myfinance.repository.WalletRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;


public final class FinanceManager {
    private final AuthService auth;
    private final WalletRepository snapshots;

    public FinanceManager(AuthService auth) {
        this(auth, null);
    }

    public FinanceManager(AuthService auth, WalletRepository snapshots) {
        this.auth = Objects.requireNonNull(auth);
        this.snapshots = snapshots;
    }

    public void createCategory(String name, Category.Type type) {
        wallet().createCategory(name, type);
    }

    public List<Category> getCategories() {
        return wallet().getCategories();
    }

    public List<String> addIncome(String categoryName, BigDecimal amount, LocalDate date) {
        Wallet wallet = wallet();
        return wallet.addTransactionWithAlerts(new Income(amount, date, wallet.getCategory(categoryName)));
    }

    public List<String> addExpense(String categoryName, BigDecimal amount, LocalDate date) {
        Wallet wallet = wallet();
        return wallet.addTransactionWithAlerts(new Expense(amount, date, wallet.getCategory(categoryName)));
    }

    public void renameCategory(String oldName, String newName) {
        wallet().renameCategory(oldName, newName);
    }

    public void deleteCategory(String name) {
        wallet().deleteCategory(name);
    }

    public void removeBudget(String name, YearMonth month) {
        wallet().removeBudget(name, month);
    }

    public Map<Category, BigDecimal> getTotalsForCategories(Category.Type type,
                                                           Collection<String> names, YearMonth month) {
        return new ReportGenerator().getTotalsForCategories(wallet(), type, names, month);
    }

    public String generateCsv() {
        return new ReportGenerator().generateCsv(wallet());
    }

    public void exportJson(String fileName) throws IOException {
        User user = auth.getCurrentUser();
        requireSnapshots().writeSnapshot(user.getLogin(), user.getWallet(), fileName);
    }

    public PreparedImport prepareImport(String fileName) throws IOException {
        User user = auth.getCurrentUser();
        Wallet restored = requireSnapshots().readSnapshot(user.getLogin(), fileName);
        return new PreparedImport(user, restored);
    }


    public void applyImport(PreparedImport prepared) {
        User user = auth.getCurrentUser();
        if (prepared == null || prepared.owner != user) {
            throw new IllegalArgumentException("Импорт подготовлен для другого пользователя.");
        }
        user.replaceWallet(prepared.wallet);
    }

    public static final class PreparedImport {
        private final User owner;
        private final Wallet wallet;

        private PreparedImport(User owner, Wallet wallet) {
            this.owner = owner;
            this.wallet = wallet;
        }
    }

    private WalletRepository requireSnapshots() {
        if (snapshots == null) {
            throw new IllegalStateException("Файловое хранилище не подключено.");
        }
        return snapshots;
    }

    public BigDecimal getBalance() {
        return wallet().getBalance();
    }

    public List<String> setBudget(String categoryName, YearMonth month, BigDecimal limit) {
        Wallet wallet = wallet();
        wallet.setBudget(categoryName, month, limit);
        return wallet.getBudgetAlerts(categoryName, month);
    }

    public List<Wallet.BudgetStatus> getBudgetStatuses(YearMonth month) {
        return wallet().getBudgetStatuses(month);
    }

    public List<String> getAlerts(YearMonth month) {
        return wallet().getAlerts(month);
    }

    public List<Transaction> getTransactions() {
        return sortedTransactions(new Transaction.DateComparator());
    }

    public List<Transaction> getTransactionsByAmount() {
        return sortedTransactions(new Comparator<Transaction>() {
            @Override
            public int compare(Transaction first, Transaction second) {
                return first.getAmount().compareTo(second.getAmount());
            }
        });
    }

    public String generateReport(Report report, YearMonth month) {
        return Objects.requireNonNull(report).generate(wallet(), month);
    }

    private List<Transaction> sortedTransactions(Comparator<Transaction> comparator) {
        List<Transaction> result = new ArrayList<>(wallet().getTransactions());
        result.sort(comparator);
        return List.copyOf(result);
    }

    private Wallet wallet() {
        return auth.getCurrentUser().getWallet();
    }
}
