package com.mockcoin.shared.apidocs.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

// 서비스 API 명세(OpenAPI JSON)를 커밋된 파일과 비교하거나(기본) 다시 써서 갱신한다(mockcoin.apidocs.update=true).
// 파일 위치는 Gradle이 넘기는 mockcoin.apidocs.dir(= server/docs/generated/api)이다.
public final class ApiDocsSnapshot {
    static final String DIR_PROPERTY = "mockcoin.apidocs.dir";
    static final String UPDATE_PROPERTY = "mockcoin.apidocs.update";
    private static final String REGENERATE_HINT = "cd server && ./scripts/generate-api-docs.sh";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private ApiDocsSnapshot() {
    }

    // 명세를 정렬·들여쓰기가 고정된 형식으로 바꾼다(줄바꿈 LF, 끝 줄바꿈 포함)
    public static String canonical(String specJson) {
        try {
            Map<?, ?> tree = MAPPER.readValue(specJson, Map.class);
            DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                    .withSeparators(Separators.createDefaultInstance()
                            .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                            .withObjectEmptySeparator("")
                            .withArrayEmptySeparator(""));
            printer.indentObjectsWith(indenter);
            printer.indentArraysWith(indenter);
            return MAPPER.writer(printer).writeValueAsString(tree) + "\n";
        } catch (IOException exception) {
            throw new IllegalStateException("API 명세 JSON을 읽지 못했습니다.", exception);
        }
    }

    // 갱신 모드면 파일에 쓰고, 아니면 커밋된 파일과 다를 때 실패한다
    public static void verify(String serviceName, String specJson) {
        String actual = canonical(specJson);
        Path file = directory().resolve(serviceName + ".json");
        if (Boolean.parseBoolean(System.getProperty(UPDATE_PROPERTY))) {
            write(file, actual);
            return;
        }
        String expected = read(file);
        if (actual.equals(expected)) {
            return;
        }
        Path actualCopy = Path.of("build", "api-docs", serviceName + ".json").toAbsolutePath();
        write(actualCopy, actual);
        throw new AssertionError("API 명세가 코드와 다릅니다: " + file
                + "\n코드에서 다시 만든 명세: " + actualCopy
                + "\n" + firstDifference(expected, actual)
                + "\n의도한 변경이면 `" + REGENERATE_HINT + "`로 명세를 다시 만들어 함께 커밋하세요.");
    }

    static Path directory() {
        String directory = System.getProperty(DIR_PROPERTY);
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException(DIR_PROPERTY + " 시스템 속성이 없습니다. Gradle 테스트 태스크로 실행하세요.");
        }
        return Path.of(directory);
    }

    private static String read(Path file) {
        if (!Files.exists(file)) {
            throw new AssertionError("커밋된 API 명세가 없습니다: " + file + "\n`" + REGENERATE_HINT + "`로 만들어 커밋하세요.");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("API 명세 파일을 읽지 못했습니다: " + file, exception);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("API 명세 파일을 쓰지 못했습니다: " + file, exception);
        }
    }

    private static String firstDifference(String expected, String actual) {
        String[] expectedLines = expected.split("\n", -1);
        String[] actualLines = actual.split("\n", -1);
        int lines = Math.min(expectedLines.length, actualLines.length);
        for (int index = 0; index < lines; index++) {
            if (!expectedLines[index].equals(actualLines[index])) {
                return "첫 차이(" + (index + 1) + "행)\n  커밋된 명세: " + expectedLines[index].strip()
                        + "\n  코드의 명세: " + actualLines[index].strip();
            }
        }
        return "줄 수가 다릅니다(커밋된 명세 " + expectedLines.length + "행, 코드의 명세 " + actualLines.length + "행).";
    }
}
