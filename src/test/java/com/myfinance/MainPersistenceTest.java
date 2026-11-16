package com.myfinance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MainPersistenceTest {
    @TempDir
    Path temporary;

    private static String registration() {
        return "1\nalice\npassword1\n2\nalice\npassword1\n";
    }

    private static String initialWallet() {
        return "1\nЗарплата\n1\n1\nЕда\n2\n2\nЗарплата\n10000\n2026-11-01\n"
                + "4\nЕда\n2026-11\n4000\n3\nЕда\n500\n2026-11-09\n";
    }

    @Test
    void twoConsoleRunsSaveAndRestoreTheFullWallet() {
        String first = execute(registration() + initialWallet() + "0\n");
        assertTrue(first.contains("Данные сохранены."));
        String second = execute("2\nalice\nwrongpass\n2\nalice\npassword1\n5\n8\n6\n7\n2026-11\n0\n");
        assertTrue(second.contains("Неверный логин или пароль"));
        assertTrue(second.contains("Баланс: 9500.00 RUB"));
        assertTrue(second.contains("2026-11-09 | Еда | -500.00 RUB"));
        assertTrue(second.lines().map(line -> line.strip().replaceAll("\\s*\\|\\s*", " | "))
                .anyMatch(line -> line.equals("Еда | 4000.00 | 500.00 | 3500.00")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void settingOrReducingBudgetPrintsOnlySelectedCategoryAndMonth(boolean existingBudget) {
        String setup = execute(registration() + initialWallet() + """
                1
                Такси
                2
                3
                Такси
                150
                2026-11-09
                4
                Еда
                2026-11
                100
                4
                Такси
                2026-12
                10
                3
                Такси
                20
                2026-12-01
                """ + (existingBudget ? "4\nТакси\n2026-11\n200\n" : "") + "0\n");
        assertFalse(setup.contains("Ошибка"));
        String text = execute("2\nalice\npassword1\n4\nТакси\n2026-11\n100\n0\n");
        String normalizedOutput = text.replace("\r\n", "\n");
        assertTrue(normalizedOutput.contains(
                "Бюджет установлен.\nПревышен бюджет категории «Такси» за 2026-11 на 50.00 RUB."), text);
        assertEquals(1, text.lines().filter(line -> line.startsWith("Превышен бюджет")).count());
        assertFalse(text.contains("Превышен бюджет категории «Еда»"));
        assertFalse(text.contains("Превышен бюджет категории «Такси» за 2026-12"));
        assertFalse(text.contains("Ошибка"));
    }

    @Test
    void repeatedCategoryNamesInConsoleAreCountedOnce() {
        String text = execute(registration() + initialWallet() + "14\n2\n3\nЕда\nеДА\n Еда \n2026-11\n0\n");
        assertTrue(text.contains("Итого: 500.00 RUB"));
        assertFalse(text.contains("Ошибка"));
    }

    @Test
    void eofMidCommandSavesCompletedOperations() {
        String first = execute(registration() + initialWallet() + "1\nНедописанная категория\n");
        assertTrue(first.contains("Ввод завершён"));
        assertTrue(first.contains("Данные сохранены."));
        String second = execute("2\nalice\npassword1\n5\n8\n0\n");
        assertTrue(second.contains("Баланс: 9500.00 RUB"));
        assertFalse(second.contains("Недописанная категория"));
    }

    @Test
    void importCancellationAndConfirmationAreDifferentAndFilesCanBeExported() throws IOException {
        String text = execute(registration() + initialWallet() + """
                16
                wallet.json
                3
                Еда
                10
                2026-11-09
                17
                wallet.json
                нет
                5
                17
                wallet.json
                да
                5
                15
                operations.csv
                18
                2026-11
                report.txt
                0
                """);
        assertTrue(text.contains("Импорт отменён"));
        assertTrue(text.contains("Баланс: 9490.00 RUB"));
        assertTrue(text.contains("Кошелёк восстановлен из JSON"));
        assertTrue(text.contains("Баланс: 9500.00 RUB"));
        assertFalse(text.contains("Ошибка"));
        assertTrue(Files.readString(temporary.resolve("exports/operations.csv")).contains("2026-11-09,EXPENSE,Еда,500.00"));
        assertTrue(Files.readString(temporary.resolve("exports/report.txt")).contains("Расходы: 500.00 RUB"));
    }

    @Test
    void helpEditingSelectionsAndBudgetRemovalWorkThroughConsole() {
        String text = execute("help\n" + registration() + initialWallet() + """
                12
                Еда
                Питание
                1
                Такси
                2
                14
                2
                2
                Питание
                Такси
                2026-11
                19
                Питание
                2026-11
                13
                Такси
                13
                Питание
                0
                """);
        assertTrue(text.contains("СПРАВКА"));
        assertTrue(text.contains("Категория переименована"));
        assertTrue(text.contains("Питание: 500.00 RUB"));
        assertTrue(text.contains("Такси: 0.00 RUB"));
        assertTrue(text.contains("Итого: 500.00 RUB"));
        assertTrue(text.contains("Бюджет удалён; операции сохранены"));
        assertTrue(text.contains("Категория и её неиспользуемые бюджеты удалены"));
        assertTrue(text.contains("Категория используется в операциях"));
    }

    @Test
    void invalidJsonAndUnsafeExportNameDoNotCrashConsoleOrChangeWallet() throws IOException {
        Files.createDirectories(temporary.resolve("exports"));
        Files.writeString(temporary.resolve("exports/broken.json"), "broken");
        String text = execute(registration() + initialWallet() + "17\nbroken.json\n15\n../bad.csv\n5\n0\n");
        assertTrue(text.contains("Ошибка файла"));
        assertTrue(text.contains("без пути"));
        assertTrue(text.contains("Баланс: 9500.00 RUB"));
        assertTrue(text.contains("Данные сохранены."));
    }

    @Test
    void corruptUsersFileDoesNotGetReplacedByEmptyStore() throws IOException {
        Files.createDirectories(temporary.resolve("data"));
        Path file = temporary.resolve("data/users.json");
        Files.writeString(file, "broken");
        String text = execute(registration() + "0\n");
        assertTrue(text.contains("Ошибка открытия хранилища"));
        assertTrue(text.contains("Запуск остановлен"));
        assertEquals("broken", Files.readString(file));
    }

    @Test
    void eofAfterFailedSaveNeverClaimsSuccess() throws IOException {
        Path blocker = temporary.resolve("data/wallets");
        Files.createDirectories(blocker.getParent());
        Files.writeString(blocker, "blocked");
        String text = execute(registration() + initialWallet() + "0\n");
        assertTrue(text.contains("Ошибка сохранения"));
        assertTrue(text.contains("Сохранение не выполнено"));
        assertFalse(text.contains("Данные сохранены."));
    }

    @Test
    void registrationIsPersistedEvenWithoutLogin() {
        assertTrue(execute("1\nalice\npassword1\n0\n").contains("Регистрация выполнена"));
        assertTrue(execute("2\nalice\npassword1\n0\n").contains("Вход выполнен"));
    }

    private String execute(String commands) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Scanner input = new Scanner(commands);
             PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            Main.run(input, out, temporary.resolve("data"), temporary.resolve("exports"));
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
