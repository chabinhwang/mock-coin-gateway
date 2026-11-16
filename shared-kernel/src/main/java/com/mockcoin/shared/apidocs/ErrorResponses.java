package com.mockcoin.shared.apidocs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mockcoin.shared.api.ErrorCode;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springframework.http.HttpStatus;

// ErrorCode 정의를 OpenAPI 에러 응답(상태 코드별 응답, 에러 코드별 예시, 설명)으로 옮긴다.
// 예시 이름은 ErrorCode 상수 이름이며, 한 서비스 안에서 이름이 같은 서로 다른 정의는 허용하지 않는다.
final class ErrorResponses {
    static final String FAIL_SCHEMA = "ApiFailResponse";
    static final String JSON = "application/json";

    private final Map<String, ErrorCode> definitions = new ConcurrentHashMap<>();

    // 연산에 에러 하나를 더한다. 같은 이름의 예시가 이미 있으면 그대로 둔다.
    void add(Operation operation, DocumentedError error) {
        ErrorCode definition = error.definition();
        ErrorCode registered = definitions.putIfAbsent(definition.name(), definition);
        if (registered != null && registered != definition) {
            throw new IllegalStateException("에러 예시 이름이 서로 다른 정의에 중복됩니다: "
                    + describe(registered) + ", " + describe(definition));
        }
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        String status = String.valueOf(error.status());
        ApiResponse response = operation.getResponses().get(status);
        if (response == null) {
            response = new ApiResponse();
            operation.getResponses().addApiResponse(status, response);
        }
        if (response.getContent() == null) {
            response.setContent(new Content());
        }
        MediaType mediaType = response.getContent().get(JSON);
        if (mediaType == null) {
            mediaType = new MediaType().schema(new Schema<>().$ref(FAIL_SCHEMA));
            response.getContent().addMediaType(JSON, mediaType);
        }
        if (mediaType.getExamples() == null || !mediaType.getExamples().containsKey(definition.name())) {
            mediaType.addExamples(definition.name(), example(error));
        }
    }

    // 에러 예시가 달린 응답마다 설명을 에러 코드 목록으로 다시 쓴다
    void describe(Operation operation) {
        if (operation.getResponses() == null) {
            return;
        }
        operation.getResponses().forEach((status, response) -> {
            Map<String, ErrorCode> errors = errorsOf(response);
            if (!errors.isEmpty()) {
                response.setDescription(responseDescription(status, errors));
            }
        });
    }

    // 응답의 JSON 예시 이름을 정렬해 등록된 에러 정의만 모아 돌려주고, 예시가 없으면 빈 맵을 돌려준다.
    private Map<String, ErrorCode> errorsOf(ApiResponse response) {
        Map<String, ErrorCode> errors = new LinkedHashMap<>();
        if (response.getContent() == null || response.getContent().get(JSON) == null
                || response.getContent().get(JSON).getExamples() == null) {
            return errors;
        }
        response.getContent().get(JSON).getExamples().keySet().stream()
                .sorted()
                .forEach(name -> {
                    ErrorCode definition = definitions.get(name);
                    if (definition != null) {
                        errors.put(name, definition);
                    }
                });
        return errors;
    }

    // 상태 코드 제목 아래 에러별 발생 조건·대응·재시도 여부를 마크다운 목록으로 쓴 응답 설명을 만든다.
    private static String responseDescription(String status, Map<String, ErrorCode> errors) {
        HttpStatus httpStatus = HttpStatus.resolve(Integer.parseInt(status));
        StringBuilder description = new StringBuilder()
                .append("**").append(status);
        if (httpStatus != null) {
            description.append(' ').append(httpStatus.getReasonPhrase());
        }
        description.append("** — 아래 에러 중 하나다. 본문 `error.errorCode`로 구분한다.\n");
        errors.values().forEach(definition -> description.append('\n')
                .append("- ").append(title(definition)).append(" — ").append(definition.when())
                .append("\n  - 대응: ").append(definition.action())
                .append(" (재시도 ").append(definition.retryable() ? "가능" : "불가").append(')'));
        return description.toString();
    }

    // 에러 정의로 실패 봉투 예시 본문과 요약·발생 조건·대응·재시도 설명을 담은 예시를 만든다.
    private static Example example(DocumentedError error) {
        ErrorCode definition = error.definition();
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("errorCode", definition.code());
        failure.put("reason", error.exampleReason());
        body.put("resultType", "FAIL");
        body.put("success", null);
        body.put("error", failure);
        return new Example()
                .summary(definition.code().equals(definition.name())
                        ? definition.code()
                        : definition.name() + " (" + definition.code() + ")")
                .description("**발생 조건** " + definition.when()
                        + "\n\n**대응** " + definition.action()
                        + "\n\n**재시도** " + (definition.retryable() ? "가능" : "불가"))
                .value(body);
    }

    // 에러 코드를(상수 이름이 다르면 이름도 함께) 굵은 코드 서식 제목으로 만든다.
    private static String title(ErrorCode definition) {
        return definition.code().equals(definition.name())
                ? "**`" + definition.code() + "`**"
                : "**`" + definition.code() + "`** (`" + definition.name() + "`)";
    }

    // 중복 오류 메시지에 쓸 '클래스명.상수명' 문자열을 만든다.
    private static String describe(ErrorCode definition) {
        return definition.getClass().getName() + "." + definition.name();
    }
}
