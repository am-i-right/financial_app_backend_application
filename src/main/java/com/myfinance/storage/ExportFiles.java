package com.myfinance.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;


public final class ExportFiles {
    private ExportFiles() {
    }

    public static Path resolve(Path directory, String fileName, String extension) throws IOException {
        if (fileName == null || !fileName.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,99}")
                || !fileName.toLowerCase(Locale.ROOT).endsWith(extension)) {
            throw new IllegalArgumentException("Укажите имя файла с расширением " + extension
                    + ": буквы, цифры, точка, дефис или подчёркивание; без пути.");
        }
        Path target = directory.toAbsolutePath().normalize().resolve(fileName);
        checkPath(target);
        return target;
    }

    static void checkPath(Path path) throws IOException {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Символические ссылки не поддерживаются: " + current);
            }
        }
    }

    static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    static void requireRegularFile(Path path) throws IOException {
        checkPath(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Файл не найден или не является обычным файлом: " + path);
        }
    }

    public static void writeText(Path target, String text) throws IOException {
        Path file = target.toAbsolutePath().normalize();
        checkPath(file);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".finance-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
