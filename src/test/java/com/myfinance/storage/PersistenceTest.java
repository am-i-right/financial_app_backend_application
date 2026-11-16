package com.myfinance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinance.model.Category;
import com.myfinance.model.User;
import com.myfinance.service.AuthService;
import com.myfinance.service.FinanceManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistenceTest {
    @TempDir
    Path temporary;
    private final LocalDate date = LocalDate.of(2026, 11, 9);

    private JsonWalletRepository wallets() {
        return new JsonWalletRepository(temporary.resolve("data"), temporary.resolve("exports"));
    }

    private AuthService auth() throws IOException {
        return new AuthService(new JsonUserRepository(temporary.resolve("data")), wallets());
    }

    @Test
    void exactAcceptanceScenarioRestoresCredentialsWalletAndMonthlyBudgets() throws IOException {
        AuthService first = auth();
        first.register("alice", "password1");
        first.login("alice", "password1");
        FinanceManager finance = new FinanceManager(first);
        finance.createCategory("Зарплата", Category.Type.INCOME);
        finance.createCategory("Еда", Category.Type.EXPENSE);
        finance.addIncome("Зарплата", new BigDecimal("10000"), date);
        finance.setBudget("Еда", YearMonth.from(date), new BigDecimal("4000"));
        finance.setBudget("Еда", YearMonth.from(date).plusMonths(1), new BigDecimal("5000"));
        finance.addExpense("Еда", new BigDecimal("500"), date);
        User.Credentials originalCredentials = first.getCurrentUser().getCredentials();
        first.saveAll();

        AuthService second = auth();
        assertThrows(IllegalArgumentException.class, () -> second.login("alice", "wrongpass"));
        assertFalse(second.isLoggedIn());
        second.login("alice", "password1");
        FinanceManager restored = new FinanceManager(second);
        assertEquals(new BigDecimal("9500.00"), restored.getBalance());
        assertEquals(2, restored.getCategories().size());
        assertEquals(2, restored.getTransactions().size());
        assertEquals(new BigDecimal("4000.00"), restored.getBudgetStatuses(YearMonth.from(date)).get(0).limit());
        assertEquals(new BigDecimal("3500.00"), restored.getBudgetStatuses(YearMonth.from(date)).get(0).remaining());
        assertEquals(new BigDecimal("5000.00"),
                restored.getBudgetStatuses(YearMonth.from(date).plusMonths(1)).get(0).limit());
        assertEquals(originalCredentials, second.getCurrentUser().getCredentials());
        assertSame(second.getCurrentUser().getWallet().getCategory("Еда"),
                restored.getTransactions().get(1).getCategory());
        assertEquals(date, restored.getTransactions().get(1).getDate());
        String usersJson = Files.readString(temporary.resolve("data/users.json"));
        assertFalse(usersJson.contains("password1"));
        assertTrue(usersJson.contains("PBKDF2WithHmacSHA256"));
    }

    @Test
    void switchingUsersSavesEachWalletBeforeRestart() throws IOException {
        AuthService first = auth();
        first.register("alice", "password1");
        first.register("bob", "password2");
        first.login("alice", "password1");
        FinanceManager finance = new FinanceManager(first);
        finance.createCategory("Зарплата", Category.Type.INCOME);
        finance.createCategory("Еда", Category.Type.EXPENSE);
        finance.addIncome("Зарплата", BigDecimal.TEN, date);
        finance.setBudget("Еда", YearMonth.from(date), BigDecimal.ONE);
        finance.addExpense("Еда", BigDecimal.ONE, date);
        first.logout();
        first.login("bob", "password2");
        finance.createCategory("Бонус", Category.Type.INCOME);
        finance.addIncome("Бонус", new BigDecimal("200"), date);
        first.saveAll();
        AuthService second = auth();
        second.login("bob", "password2");
        FinanceManager restored = new FinanceManager(second);
        assertEquals(new BigDecimal("200.00"), restored.getBalance());
        assertEquals(List.of("Бонус"), restored.getCategories().stream().map(Category::getName).toList());
        assertTrue(restored.getBudgetStatuses(YearMonth.from(date)).isEmpty());
        second.logout();
        second.login("alice", "password1");
        assertEquals(new BigDecimal("9.00"), restored.getBalance());
        assertEquals(2, restored.getTransactions().size());
        assertEquals(1, restored.getBudgetStatuses(YearMonth.from(date)).size());
    }

    @Test
    void renamedCategoryKeepsReferencesAfterRestart() throws IOException {
        AuthService first = auth();
        first.register("alice", "password1");
        first.login("alice", "password1");
        FinanceManager finance = new FinanceManager(first);
        finance.createCategory("Еда", Category.Type.EXPENSE);
        finance.addExpense("Еда", BigDecimal.ONE, date);
        finance.setBudget("Еда", YearMonth.from(date), BigDecimal.TEN);
        finance.renameCategory("Еда", "Питание");
        first.logout();
        AuthService second = auth();
        second.login("alice", "password1");
        Category category = second.getCurrentUser().getWallet().getCategory("Питание");
        assertSame(category, second.getCurrentUser().getWallet().getTransactions().get(0).getCategory());
        assertSame(category, second.getCurrentUser().getWallet().getBudgetStatuses(YearMonth.from(date)).get(0).category());
        assertThrows(IllegalArgumentException.class, () -> second.getCurrentUser().getWallet().getCategory("Еда"));
    }

    @Test
    void corruptUsersFileStopsLoadingAndRemainsUntouched() throws IOException {
        Files.createDirectories(temporary.resolve("data"));
        Path file = temporary.resolve("data/users.json");
        Files.writeString(file, "{broken");
        assertThrows(IOException.class, this::auth);
        assertEquals("{broken", Files.readString(file));
        assertThrows(IOException.class, () -> new JsonUserRepository(temporary.resolve("data")).save(List.of()));
        assertEquals("{broken", Files.readString(file));
    }

    @Test
    void corruptWalletDoesNotCreateSessionOrOverwriteSavedFile() throws IOException {
        AuthService first = auth();
        first.register("alice", "password1");
        first.login("alice", "password1");
        first.logout();
        Path file = onlyWalletFile();
        Files.writeString(file, "{broken");
        AuthService second = auth();
        assertThrows(UncheckedIOException.class, () -> second.login("alice", "password1"));
        assertFalse(second.isLoggedIn());
        second.saveAll();
        assertEquals("{broken", Files.readString(file));
    }

    @Test
    void failedLogoutRetainsSessionAndDataForRetry() throws IOException {
        AuthService first = auth();
        first.register("alice", "password1");
        first.login("alice", "password1");
        FinanceManager finance = new FinanceManager(first);
        finance.createCategory("Еда", Category.Type.EXPENSE);
        finance.addExpense("Еда", BigDecimal.ONE, date);
        Path blocker = temporary.resolve("data/wallets");
        Files.writeString(blocker, "This regular file blocks the wallet directory.");
        assertThrows(UncheckedIOException.class, first::logout);
        assertTrue(first.isLoggedIn());
        assertEquals(new BigDecimal("-1.00"), finance.getBalance());
        Files.delete(blocker);
        first.logout();
        assertFalse(first.isLoggedIn());
        AuthService second = auth();
        second.login("alice", "password1");
        assertEquals(new BigDecimal("-1.00"), new FinanceManager(second).getBalance());
    }

    @Test
    void corruptionAfterLoginIsNotOverwrittenOnSave() throws IOException {
        AuthService auth = auth();
        auth.register("alice", "password1");
        auth.login("alice", "password1");
        auth.saveAll();
        Path file = onlyWalletFile();
        Files.writeString(file, "broken after login");
        assertThrows(UncheckedIOException.class, auth::saveAll);
        assertEquals("broken after login", Files.readString(file));
        assertTrue(auth.isLoggedIn());
    }

    @Test
    void registrationWriteFailureCanBeRetriedWithoutGhostUser() throws IOException {
        AuthService auth = auth();
        Files.createDirectories(temporary.resolve("data/users.json"));
        assertThrows(UncheckedIOException.class, () -> auth.register("alice", "password1"));
        Files.delete(temporary.resolve("data/users.json"));
        auth.register("alice", "password1");
        auth.login("alice", "password1");
        assertTrue(auth.isLoggedIn());
    }

    @Test
    void pathLikeLoginCannotEscapeWalletDirectory() throws IOException {
        AuthService first = auth();
        first.register("../../alice", "password1");
        first.login("../../alice", "password1");
        first.logout();
        assertTrue(onlyWalletFile().getFileName().toString().matches("[0-9a-f]{64}\\.json"));
        AuthService second = auth();
        second.login("../../alice", "password1");
        assertTrue(second.isLoggedIn());
    }

    private Path onlyWalletFile() throws IOException {
        try (var files = Files.list(temporary.resolve("data/wallets"))) {
            return files.findFirst().orElseThrow();
        }
    }
}
