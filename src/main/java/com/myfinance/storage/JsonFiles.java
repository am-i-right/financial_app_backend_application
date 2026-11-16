package com.myfinance.storage;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;


final class JsonFiles {
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private JsonFiles() {
    }

    static <T> T read(Path path, Class<T> type) throws IOException {
        ExportFiles.requireRegularFile(path);
        try {
            T result = MAPPER.readValue(path.toFile(), type);
            if (result == null) {
                throw new IOException("JSON не должен быть null.");
            }
            return result;
        } catch (IOException e) {
            throw new IOException("Не удалось прочитать JSON «" + path + "»: " + e.getMessage(), e);
        }
    }

    static void write(Path path, Object data) throws IOException {
        ExportFiles.writeText(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data));
    }
}
