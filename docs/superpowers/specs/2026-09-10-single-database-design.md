# Single Database Runtime Design

## Goal

GEOSYNC 동기화 애플리케이션의 다중 대상 DB 모델을 단일 대상 DB 모델로 단순화한다. 모든 수집·적재·로그·상태 확인 작업은 한 DB만 사용한다. 관리 화면에서 DB 접속 정보를 변경하고 재시작 없이 적용하는 기존 운영 기능은 유지한다.

## Scope

- `spring.datasource`를 유일한 영구 DB 설정으로 사용한다.
- 다중 DB 설정 모델, 대상 선택 UI, 대상별 반복 적재, 대상별 스키마 매핑을 제거한다.
- 기존 HTTP URL, HTTP 메서드, 요청 파라미터 이름과 응답의 최상위 형태는 유지한다.
- 기존 `targets[]` 설정 파일을 읽을 수 있게 하며, 다음 설정 저장 시 단일 설정 형식으로 변환한다.
- KRAS 데이터 취득, 파일 생성, SQL 생성, 좌표 변환 로직은 변경하지 않는다.

## Non-goals

- DB 장애 조치나 읽기/쓰기 분리 기능을 추가하지 않는다.
- 실행 중 여러 DB 사이를 선택하는 기능을 유지하지 않는다.
- API 버전을 올리거나 기존 엔드포인트를 삭제하지 않는다.
- DB 비밀번호 저장 정책을 이번 변경에서 새로 설계하지 않는다.

## Configuration Model

정식 설정 형식은 다음과 같다.

```yaml
geosync:
  database:
    display-name: "운영 DB"

spring:
  datasource:
    url: "jdbc:postgresql://host:5432/database"
    username: "postgres"
    password: ""
```

`spring.datasource`만 연결 설정을 소유한다. 화면과 호환 응답에 사용하는 표시 이름은
`geosync.database.display-name`에 저장하며, 값이 없으면 JDBC URL의 DB명을 사용한다.

런타임 설정 로더는 URL, 사용자명, 비밀번호를 서로 다른 출처에서 섞지 않고 다음
우선순위로 하나의 완전한 설정 묶음을 선택한다.

1. 외부 설정 파일의 `spring.datasource`
2. 외부 설정 파일에 단일 설정이 없을 때 기존 `targets[]`의 첫 번째 활성 항목
3. 애플리케이션 시작 시 Spring이 구성한 기본 `DataSource`

기존 `targets[]`에서 마이그레이션할 때는 첫 번째 `enabled: true` 항목을 사용한다. 활성 항목이 없으면 첫 번째 유효 항목을 사용한다. 설정 화면에서 저장하면 `targets[]`는 쓰지 않고 `spring.datasource`만 쓴다.

외부 YAML의 `${NAME:default}` placeholder는 Spring `Environment`로 해석한 유효 값을
연결과 지문 계산에 사용한다. 화면에는 유효 값을 표시하되, 사용자가 저장하기 전에는
원본 placeholder를 변경하지 않는다. URL과 사용자명 중 하나만 있는 불완전한 단일 설정은
오류로 기록하고 시작 시 `DataSource`로 폴백하며, 레거시 설정과 필드 단위로 혼합하지 않는다.

설정 저장은 기존 YAML 트리를 읽어 관리 대상 키만 갱신한다. `server`, `logging`,
`spring.datasource.hikari`, `koreps`, `mock`, `conn-log` 및 알 수 없는 키는 보존하고,
`targets` 키만 제거한다. 임시 파일을 같은 디렉터리에 쓴 뒤 원자적 이동으로 교체하여
부분 파일을 런타임 로더가 읽지 않게 한다. SnakeYAML 재직렬화로 주석이나 서식은 바뀔 수
있지만 값과 미관리 키는 보존한다.

## Runtime Connection Management

단일 DB 연결 서비스는 현재 설정의 URL, 사용자명, 비밀번호를 지문으로 관리한다. DB가 필요할 때 현재 외부 설정을 읽고 지문이 바뀌었는지 확인한다. 외부 파일 변경은 파일 수정 시간과 내용 해시로 감지하고, 관리 화면 저장은 명시적 `refresh()`를 호출한다.

- 지문이 같으면 기존 `JdbcTemplate`과 HikariCP 풀을 재사용한다.
- 지문이 다르면 새 풀을 만들고 `SELECT 1`로 연결 유효성을 확인한 뒤 원자적으로 교체한다.
- 새 연결 생성이나 검증이 실패하면 기존 연결을 유지하고 오류를 반환하거나 기록한다.
- 관리 화면 저장은 새 연결을 먼저 검증한다. 검증 실패 시 파일과 현재 연결을 모두 변경하지 않는다.
- 교체가 성공하면 이전 풀을 retired 상태로 옮기고, 그 풀을 사용하는 작업 lease가 모두 반환된 뒤 닫는다.
- 애플리케이션 종료 시 동적 풀을 닫는다.
- 외부 설정에 DB 값이 없으면 시작 시 만들어진 Spring 기본 `JdbcTemplate`을 사용한다.

공개 내부 API는 `DatabaseSession acquire()`처럼 단일 DB 의미가 드러나는 단수형 접근을
사용한다. `DatabaseSession`은 표시 이름, URL, `JdbcTemplate`, 설정 지문을 가진 읽기 전용
스냅샷이며 `AutoCloseable` lease로 동작한다. 한 동기화 작업은 시작부터 데이터 적재와 실행
로그 완료까지 같은 세션을 사용한다. 기존 HTTP 응답 호환을 위해 필요한 컨트롤러 경계에서만
한 항목짜리 목록으로 변환한다.

새 연결이 게시되면 DB 변경 이벤트를 발행한다. `SyncExecutionLogService`와
`ConnRequestLogService`는 새 DB에서 스키마·로그 테이블·인덱스를 멱등적으로 확인하고,
초기화 실패 시 다음 접근에서 다시 시도한다. `UsezoneCodeService`와
`KrasCatalogStatusService`는 DB에 종속된 캐시를 즉시 비운다. 종료 시에는 신규 lease 발급을
중지하고 기존 lease가 끝날 때까지 제한 시간 동안 기다린 뒤 모든 동적 풀을 닫는다.

## Synchronization Flow

KRAS 수동 적재, 예약 적재, mock 적재와 기존 파일 기반 적재는 모두 다음 흐름을 사용한다.

1. 실행 전체에서 유지할 단일 `DatabaseSession`을 얻는다.
2. 요청에서 전달된 스키마가 있으면 사용하고, 없으면 `ods.schema`를 사용한다.
3. 선택된 테이블을 한 번씩 단일 DB에 적재한다.
4. 실행 시작·완료 로그와 상태 정보도 같은 세션의 DB에 기록한다.
5. 작업이 끝난 뒤 세션을 반환하여 교체 대기 중인 이전 풀이 안전하게 닫히게 한다.

`KrasWorker`의 대상 목록, `TargetWithSchema`, 대상 인덱스 필터, 대상별 반복문을 제거한다. `SyncScheduler` 내부 호출도 단일 스키마와 테이블 필터만 전달한다.

## HTTP Compatibility

기존 54개 엔드포인트 계약을 유지한다. 특히 다음 호환 규칙을 적용한다.

- `targetIdx`, `target_index`, `schema_<index>` 등 기존 파라미터 선언은 제거하지 않는다.
- `targetIdx`, `target_index`, ODS API의 `idx`는 더 이상 DB 선택에 사용하지 않는다. 음수나 범위 밖 값을 포함해 어떤 값이 전달되어도 현재 단일 DB를 사용한다.
- 스키마는 비어 있지 않은 `schema_0`을 우선 사용하고, 없으면 숫자 접미사가 가장 작은 비어 있지 않은 `schema_<index>` 값, 그마저 없으면 `ods.schema`를 사용한다.
- `/db/status`는 기존 배열 응답을 유지하며 단일 DB 상태 한 건을 담는다.
- `/sync/kras-direct-load-options`는 기존 객체 응답을 유지하며 `targets` 배열에 인덱스 `0`인 단일 DB를 담는다.
- 테이블 생성 API는 기존 `targetIdx`를 받지만 단일 DB에서 실행한다.

이 규칙은 기존 브라우저 화면이나 외부 호출자가 즉시 깨지지 않게 하기 위한 경계 호환이다. 내부 도메인에는 대상 인덱스를 전달하지 않는다.

## User Interface

설정 화면의 DB 영역은 단일 카드로 바꾼다.

- DB 이름, 호스트, 포트, DB명, 사용자명, 비밀번호를 수정할 수 있다.
- DB 추가, 삭제, 활성화 체크박스를 제거한다.
- 연결 테스트와 ODS 상태 확인 기능은 유지한다.
- 저장 요청 필드명은 기존 서버 호환이 필요한 범위에서 유지할 수 있지만 화면에는 대상 인덱스 개념을 노출하지 않는다.
- JDBC URL의 query parameter는 화면에서 호스트·포트·DB명을 수정해도 보존한다. URL을 안전하게 분해할 수 없으면 원문 URL을 별도 필드에 유지하고 저장을 막는 명확한 검증 오류를 표시한다.

대시보드와 실행 화면은 다음처럼 바꾼다.

- 대상 DB 체크박스 목록 대신 현재 DB 이름과 URL을 표시한다.
- 수동 적재와 mock 적재는 대상 선택 없이 단일 DB로 실행한다.
- DB 준비 상태는 기존 카드 형태를 유지할 수 있지만 한 DB만 표시한다.
- ODS 화면과 스케줄 화면에서 다중 대상 선택이나 대상별 스키마 입력을 제거한다.

## Components

### Runtime settings

`RuntimeSettingsService`는 단일 `DatabaseSettings` 값을 제공하고, placeholder 해석과 레거시
`targets[]` 읽기 호환을 담당한다. `DatabaseSettings`에는 표시 이름, URL, 사용자명,
비밀번호가 포함된다. `TargetDbProperties`와 다중 대상 목록을 반환하는 API는 제거하고,
레거시 YAML 파싱용 타입은 서비스 내부 구현으로 한정한다.

### Database connection service

현재 `TargetDbService`의 다중 풀 캐시를 lease 기반 단일 교체 가능 풀로 바꾼다. 구현 중 이름을 단일 책임에 맞는 `DatabaseConnectionService`로 변경한다. 서비스는 연결 검증, 원자적 게시, retired 풀 정리, 종료를 소유하며 설정 YAML 저장은 담당하지 않는다.

### Controllers and database setup

`SettingsController`, `DbSetupController`, `OdsController`, `SyncController`는 단일 연결만 사용한다. HTTP 계약을 유지하기 위한 인덱스 파라미터와 한 항목 배열 변환은 컨트롤러에 한정한다. `SettingsController`의 저장 책임은 YAML 트리 병합과 원자적 파일 교체를 담당하는 별도 설정 저장 컴포넌트로 분리한다.

### Synchronization

`KrasWorker`와 `SyncScheduler`에서 대상 컬렉션과 대상별 스키마 맵을 제거한다. 예약 작업과 수동 작업이 같은 단일 DB 실행 경로를 사용하도록 정리한다. `DbSetupService`, `UsezoneCodeService`, `KrasCatalogStatusService`도 목록의 첫 항목을 선택하지 않고 전달받거나 획득한 단일 세션을 사용한다.

### Monitoring and logs

연결 요청 로그와 동기화 실행 로그는 단일 DB 연결을 사용한다. 독립적인 HTTP 요청 로그는
기록 작업을 제출할 때 세션 지문을 함께 캡처한다. 동기화 실행 로그는 동기화 세션에 귀속하여
설정 변경 중에도 시작과 완료가 같은 DB에 기록되게 한다. DB 변경 이벤트를 받은 로그 서비스는
새 DB의 로그 구조를 준비한 뒤 해당 지문을 준비 완료로 표시한다.

## Error Handling

- 설정에 JDBC URL이나 사용자명이 없으면 Spring 시작 설정으로 폴백한다.
- 단일 설정이 일부만 존재하면 레거시 값과 혼합하지 않고 설정 오류를 상태 응답과 로그에 남긴다.
- JDBC URL을 화면 필드로 분해할 수 없으면 URL 원문을 보존하고 명확한 입력 오류를 표시한다.
- 새 DB 연결 검증 실패 시 기존 풀이 있으면 이를 유지한다.
- 관리 화면에서 검증에 실패한 DB 설정은 파일에 저장하지 않는다.
- 단일 DB가 전혀 준비되지 않으면 적재를 시작하지 않고 기존 상태·오류 응답 경로로 실패를 알린다.
- 풀 교체와 종료는 동시 실행에 안전해야 하며, retired 풀은 활성 lease가 0이 된 뒤 닫는다.
- 새 DB의 로그 구조나 캐시 초기화가 실패해도 연결 자체는 유지하되 기능별 준비 실패를 표시하고 다음 접근에서 재시도한다.

## Testing Strategy

구현은 TDD 순서를 따른다.

1. 단일 `spring.datasource`를 읽는 설정 테스트를 먼저 실패시킨다.
2. 표시 이름 기본값과 `${NAME:default}` placeholder 유효 값 해석 테스트를 먼저 실패시킨다.
3. 기존 `targets[]`의 첫 활성 항목과 활성 항목이 없을 때 첫 유효 항목을 읽는 마이그레이션 테스트를 먼저 실패시킨다.
4. 불완전한 단일 설정이 레거시 설정과 섞이지 않고 시작 `DataSource`로 폴백하는 테스트를 추가한다.
5. 설정 변경 시 단일 풀을 교체하고 검증 실패 시 파일과 기존 풀을 유지하는 서비스 테스트를 먼저 실패시킨다.
6. 실행 중 lease가 있는 이전 풀이 닫히지 않고, 반환 후 정확히 한 번 닫히는 동시성 테스트를 추가한다.
7. 설정 저장 후 `targets`만 제거되고 미관리 YAML 키와 JDBC query parameter가 보존되는지 검증한다. 임시 파일 쓰기 실패 시 원본 파일이 유지되는 경우도 검증한다.
8. DB 변경 시 로그 구조가 준비되고 DB 종속 캐시가 비워지며, 초기화 실패 후 재시도되는지 검증한다.
9. 워커가 작업 전체에서 같은 세션을 사용하고 테이블별 DB 쓰기를 한 번만 수행하는 테스트를 먼저 실패시킨다.
10. MockMvc 테스트로 `/db/status`가 한 항목 배열인지, `/sync/kras-direct-load-options`가 `targets[0]`을 반환하는지, 모든 레거시 인덱스를 무시하는지 검증한다.
11. `schema_0`, 최소 숫자 접미사, `ods.schema` 순서의 스키마 선택 테스트를 추가한다.
12. 기존 `EndpointContractTest`로 54개 HTTP 매핑 계약도 유지되는지 검증한다. 이 테스트는 응답 본문 계약을 대신하지 않는다.
13. 정적 검색 또는 아키텍처 테스트로 `TargetDbService`, `TargetDbProperties`, `TargetWithSchema`, 대상 목록 반복, 대상별 스키마 맵이 남지 않았는지 검증한다.
14. 비밀 정보가 없는 기본 설정을 `DatabaseDefaultsTest`로 검증한다.
15. 전체 테스트, `bootJar`, `jlinkZip`을 실행한다.

## Migration and Compatibility

애플리케이션을 새 버전으로 처음 시작할 때 기존 `targets[]` 설정은 수정하지 않고 읽기만 한다. 관리 화면에서 저장하는 순간 같은 DB 값을 `spring.datasource`와 `geosync.database.display-name`에 기록하며 레거시 배열은 제거된다. 다른 설정 키는 보존한다.

HTTP 호출자는 기존 요청을 그대로 보낼 수 있다. 대상 선택 값은 수용되지만 단일 DB에서만 실행된다. 응답 구조 역시 배열을 기대하는 기존 JavaScript가 동작하도록 한 항목 배열을 유지한다.

기본 `application.yml`과 배포 자산에는 실제 DB 비밀번호나 서버별 자격 증명을 두지 않는다.
민감한 값은 환경변수 또는 배포 시 생성되는 외부 설정 파일로 제공한다.

## Completion Criteria

- 설정 파일의 정식 DB 정의가 `spring.datasource` 하나뿐이다.
- DB 표시 이름은 `geosync.database.display-name`에 보존되며 연결 설정과 중복되지 않는다.
- 설정 저장 후 미관리 YAML 키가 유지되고 `targets`만 제거된다.
- 설정 저장 직후 재시작 없이 새 DB 연결이 사용된다.
- 실행 중이던 작업은 교체 전 DB 세션을 끝까지 사용하고 완료 후 이전 풀이 닫힌다.
- 모든 적재 경로에서 DB 쓰기 대상이 정확히 하나다.
- DB 변경 후 로그 테이블 준비와 DB 종속 캐시 무효화가 수행된다.
- 다중 DB 추가·삭제·선택 UI가 없다.
- 기존 54개 HTTP 엔드포인트 계약 테스트가 통과한다.
- 주요 호환 응답 본문과 스키마 선택 규칙의 MockMvc 테스트가 통과한다.
- 기존 `targets[]` 설정 마이그레이션 테스트가 통과한다.
- 저장소 기본 설정에 실제 자격 증명이 없다.
- 전체 테스트와 배포 빌드가 성공한다.
- 변경은 사용자가 별도로 승인하기 전까지 커밋하지 않는다.
