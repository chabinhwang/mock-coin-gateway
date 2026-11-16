package com.mockcoin.shared.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

// MSA 서비스 메타데이터(이름, 포트, 소유 테이블 목록)를 표현하는 값 객체
@Schema(description = "서비스 경계 정보. 코드에 고정된 값을 그대로 돌려준다.")
public record ServiceDescriptor(
        @Schema(description = "서비스 이름(Gradle 모듈 이름과 같다).", example = "trading-service")
        String name,
        @Schema(description = """
                서비스 기본 포트 식별자. 값마다 로컬 기본 포트가 정해져 있다:
                `GATEWAY`=8080, `USER`=8085, `TRADING`=8082, `MARKET`=8083, `INGESTOR`=8084, `COMMUNITY`=8086.""")
        ServicePort port,
        @ArraySchema(arraySchema = @Schema(description = "이 서비스가 소유한 DB 테이블 이름. DB를 갖지 않는 서비스는 빈 배열이다."),
                schema = @Schema(example = "orders"))
        List<String> ownedTables
) {
}
