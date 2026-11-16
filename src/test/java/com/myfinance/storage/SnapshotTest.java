package com.myfinance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myfinance.model.Category;
import com.myfinance.model.Expense;
import com.myfinance.model.Wallet;
import com.myfinance.service.AuthService;
import com.myfinance.service.FinanceManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SnapshotTest {
    @TempDir
    Path temporary;
    private final LocalDate date = LocalDate.of(2026, 11, 9);
    private final ObjectMapper mapper = new ObjectMapper();
    private AuthService auth;
    private FinanceManager finance;
    private Path snapshot;

    @BeforeEach
    void createWalletAndSnapshot() throws IOException {
        auth = new AuthService();
        auth.register("alice", "password1");
        auth.login("alice", "password1");
        finance = new FinanceManager(auth, new JsonWalletRepository(temporary.resolve("data"), temporary.resolve("exports")));
        finance.createCategory("Зарплата", Category.Type.INCOME);
        finance.createCategory("Еда", Category.Type.EXPENSE);
        finance.addIncome("Зарплата", new BigDecimal("1000"), date);
        finance.addExpense("Еда", new BigDecimal("100"), date);
        finance.setBudget("Еда", YearMonth.from(date), new BigDecimal("400"));
        finance.setBudget("Еда", YearMonth.from(date).plusMonths(1), new BigDecimal("500"));
        finance.exportJson("wallet.json");
        snapshot = temporary.resolve("exports/wallet.json");
    }

    @Test
    void exportAndImportRestoreWholeSnapshotOnlyAfterApplication() throws IOException {
        String json = Files.readString(snapshot);
        assertTrue(json.contains("schemaVersion"));
        assertTrue(json.contains("alice"));
        assertFalse(json.contains("password"));
        assertFalse(json.contains("salt"));
        assertFalse(json.contains("hash"));
        finance.addExpense("Еда", BigDecimal.TEN, date);
        Wallet original = auth.getCurrentUser().getWallet();
        FinanceManager.PreparedImport prepared = finance.prepareImport("wallet.json");
        assertSame(original, auth.getCurrentUser().getWallet());
        assertEquals(new BigDecimal("890.00"), finance.getBalance());
        finance.applyImport(prepared);
        assertEquals(new BigDecimal("900.00"), finance.getBalance());
        assertEquals(2, finance.getTransactions().size());
        assertEquals(2, auth.getCurrentUser().getWallet().getAllBudgetStatuses().size());
        assertEquals(new BigDecimal("300.00"), finance.getBudgetStatuses(YearMonth.from(date)).get(0).remaining());
        assertEquals(date, finance.getTransactions().get(1).getDate());
        assertInstanceOf(Expense.class, finance.getTransactions().get(1));
        assertSame(auth.getCurrentUser().getWallet().getCategory("Еда"), finance.getTransactions().get(1).getCategory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "version", "category", "amount", "precision", "date", "type", "wrongCategoryType",
            "duplicateCategory", "duplicateBudget", "budget", "missingField", "nullField", "nullItem", "unknownField",
            "nullDocument", "broken", "trailing", "duplicateProperty"})
    void badSnapshotNeverChangesCurrentWallet(String defect) throws IOException {
        ObjectNode root = (ObjectNode) mapper.readTree(snapshot.toFile());
        ObjectNode transaction = (ObjectNode) root.withArray("transactions").get(1);
        switch (defect) {
            case "owner" -> root.put("owner", "bob");
            case "version" -> root.put("schemaVersion", 999);
            case "category" -> transaction.put("categoryName", "Нет");
            case "amount" -> transaction.put("amount", "0");
            case "precision" -> transaction.put("amount", "1.001");
            case "date" -> transaction.put("date", "2026-02-30");
            case "type" -> transaction.put("type", "TRANSFER");
            case "wrongCategoryType" -> transaction.put("type", "INCOME");
            case "duplicateCategory" -> root.withArray("categories").add(root.withArray("categories").get(0).deepCopy());
            case "duplicateBudget" -> root.withArray("budgets").add(root.withArray("budgets").get(0).deepCopy());
            case "budget" -> ((ObjectNode) root.withArray("budgets").get(0)).put("limit", "-1");
            case "missingField" -> root.remove("transactions");
            case "nullField" -> transaction.putNull("date");
            case "nullItem" -> root.withArray("categories").addNull();
            case "unknownField" -> root.put("unexpected", 1);
            default -> { }
        }
        String json = mapper.writeValueAsString(root);
        json = switch (defect) {
            case "nullDocument" -> "null";
            case "broken" -> "{broken";
            case "trailing" -> json + " {}";
            case "duplicateProperty" -> json.replaceFirst("\\{", "{\"schemaVersion\":1,");
            default -> json;
        };
        Files.writeString(snapshot, json);
        Wallet original = auth.getCurrentUser().getWallet();
        assertThrows(IOException.class, () -> finance.prepareImport("wallet.json"), defect);
        assertSame(original, auth.getCurrentUser().getWallet());
        assertEquals(new BigDecimal("900.00"), finance.getBalance());
        assertEquals(2, finance.getTransactions().size());
    }

    @Test
    void nonexistentFileAndUnauthenticatedAccessAreReported() {
        assertThrows(IOException.class, () -> finance.prepareImport("missing.json"));
        auth.logout();
        assertThrows(IllegalStateException.class, () -> finance.prepareImport("wallet.json"));
        assertThrows(IllegalStateException.class, () -> finance.exportJson("wallet.json"));
    }

    @Test
    void preparedImportCannotBeAppliedToAnotherUser() throws IOException {
        FinanceManager.PreparedImport prepared = finance.prepareImport("wallet.json");
        auth.logout();
        auth.register("bob", "password2");
        auth.login("bob", "password2");
        assertThrows(IllegalArgumentException.class, () -> finance.applyImport(prepared));
        assertTrue(finance.getCategories().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../wallet.json", "/tmp/wallet.json", "..\\wallet.json", "wallet.csv", ".json"})
    void fileNamesCannotEscapeExports(String name) {
        assertThrows(IllegalArgumentException.class, () -> finance.exportJson(name));
        assertThrows(IllegalArgumentException.class, () -> finance.prepareImport(name));
    }

    @Test
    void exportDirectorySymlinkCannotRedirectWrites() throws IOException {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path link = temporary.resolve("link");
        assumeSymbolicLinksAvailable(outside);
        Files.createSymbolicLink(link, outside);
        FinanceManager unsafe = new FinanceManager(auth, new JsonWalletRepository(temporary.resolve("data"), link));
        assertThrows(IOException.class, () -> unsafe.exportJson("wallet.json"));
        assertFalse(Files.exists(outside.resolve("wallet.json")));
    }

    @Test
    void snapshotFileSymlinkIsRejectedForReadingAndWriting() throws IOException {
        Path linkedFile = temporary.resolve("exports/linked.json");
        assumeSymbolicLinksAvailable(snapshot);
        Files.createSymbolicLink(linkedFile, snapshot);
        String original = Files.readString(snapshot);
        assertThrows(IOException.class, () -> finance.prepareImport("linked.json"));
        assertThrows(IOException.class, () -> finance.exportJson("linked.json"));
        assertEquals(original, Files.readString(snapshot));
    }

    /** Проверка возможности создания ссылки отделена от проверяемого поведения приложения. */
    private void assumeSymbolicLinksAvailable(Path target) throws IOException {
        Path probe = temporary.resolve("symlink-capability-probe");
        try {
            Files.createSymbolicLink(probe, target);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.assumeTrue(false, "Symbolic link creation is unavailable in this environment: " + e.getMessage());
        } finally {
            Files.deleteIfExists(probe);
        }
    }
}
