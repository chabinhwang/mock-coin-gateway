# Mock Coin Gateway

모의 코인 거래 서비스 **mock-coin**의 Gateway를 원본 MSA 프로젝트에서 분리한 코드입니다.
외부 REST 요청의 프록시, 사용자 인증 전파, WebSocket 이벤트 fanout, 관리자 콘솔을 담당합니다.

## 구성

```text
.
├── gateway-service/       # Gateway 애플리케이션, 관리자 UI, 테스트
├── shared-kernel/         # 공통 API 응답·에러, 보안, rate limit, 문서 검증 도구
├── docs/generated/api/    # Gateway OpenAPI 명세
├── gradle/                # 버전 카탈로그와 Gradle Wrapper
├── build.gradle           # 공통 빌드·API 문서·커버리지 검증 설정
└── settings.gradle        # Gateway와 shared-kernel만 포함
```

Gateway는 `shared-kernel`에 의존하므로 두 모듈을 함께 포함합니다.
다른 서비스의 구현 코드, 모바일 앱, 운영 환경변수 파일, 원본 Git 이력은 포함하지 않습니다.

## 주요 기능

- **REST 프록시와 인증 전파**: 요청을 담당 서비스로 전달하고, private 요청의 인증을 검증해 사용자 식별자를 전파합니다. 클라이언트가 보낸 내부 식별 헤더는 제거합니다.
- **인증 최적화**: 공유 Redis denylist를 사용하는 설정에서 JWT 로컬 검증과 원격 인증 결과 캐시를 지원합니다.
- **요청 제한**: IP·사용자·요청 종류에 따라 rate limit을 적용합니다.
- **WebSocket**: 세션·구독 관리, 연결 수 제한, heartbeat, 공개·개인 이벤트 전달 및 Redis Pub/Sub fanout을 지원합니다.
- **관리자 콘솔**: 서비스 현황 집계, 세션 인증, CSRF 방어, WebAuthn passkey 인증을 제공합니다.
- **관측과 검증**: Actuator, Prometheus, OpenTelemetry 연동 및 API 명세·JaCoCo 커버리지 검증을 포함합니다.

## 빌드와 테스트

JDK 25가 필요하며, Gradle 9.1.0은 포함된 Wrapper를 사용합니다.

```bash
./gradlew build
```

두 모듈의 테스트, Gateway 실행 JAR 생성, API 명세 일치 검사, 커버리지 하한 검증을 실행합니다.
테스트는 stub·mock으로 외부 의존성을 대체하며, 다른 서비스나 Redis 서버를 별도로 실행할 필요가 없습니다.

```bash
./gradlew :gateway-service:test
./gradlew jacocoAggregatedReport
```

## Gateway 실행

Gateway 프로세스만 실행하려면 기본 프로필을 사용하고 Redis health check를 끕니다.

```bash
./gradlew :gateway-service:bootRun --args='--management.health.redis.enabled=false'
```

기본 포트는 `8080`이며, `GET /actuator/health`로 기동 상태를 확인할 수 있습니다.
관리자 기능과 Redis 이벤트 연동은 기본적으로 꺼져 있습니다.
REST 프록시·원격 사용자 인증·관리자 집계가 실제로 동작하려면 해당 downstream 서비스가 필요합니다.
이 분리본만으로 전체 거래 서비스를 실행할 수는 없습니다.

| 대상 서비스 | 기본 주소 | 주소 변경 환경변수 |
| --- | --- | --- |
| user-service | `http://localhost:8085` | `USER_SERVICE_BASE_URL` |
| trading-service | `http://localhost:8082` | `TRADING_SERVICE_BASE_URL` |
| market-service | `http://localhost:8083` | `MARKET_SERVICE_BASE_URL` |
| ingestor-service | `http://localhost:8084` | `INGESTOR_SERVICE_BASE_URL` |
| community-service | `http://localhost:8086` | `COMMUNITY_SERVICE_BASE_URL` |

전체 설정은 [application.yml](gateway-service/src/main/resources/application.yml)을 참고하세요.
`application-local.yml`은 원본 MSA의 개발 설정으로, Redis 연동과 관리자 기능을 켜고 개발용 계정·토큰을 사용합니다.
실제 인증정보는 환경변수로 주입하고, 개발용 기본값은 운영에 사용하지 마세요.

## API 명세

[Gateway OpenAPI 명세](docs/generated/api/gateway-service.json)는 컨트롤러·DTO·에러 선언에서 생성한 파일입니다.
명세를 갱신할 때는 직접 편집하지 않고 아래 명령을 사용합니다.

```bash
./gradlew :gateway-service:generateApiDocs
```

실행 중인 애플리케이션의 `/v3/api-docs` 엔드포인트는 기본적으로 비활성화되어 있습니다.
