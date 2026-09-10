# 저장소 및 Java 패키지 재편 설계

## 1. 목적

GEOMEX 동기화 애플리케이션의 동작과 외부 계약을 유지하면서 저장소 루트와 Java 패키지를 기능 중심으로 재편한다. 이 작업은 필요한 파일을 찾기 쉽게 만들고, 각 기능의 관련 코드를 가까이 배치하며, 이후의 책임 분리 작업을 위한 명확한 경계를 제공한다.

이번 변경은 구조 리팩터링이다. 클래스 책임 분리, 기능 추가, 알려진 버그 수정, 설정 체계 변경은 범위에 포함하지 않는다.

## 2. 호환성 요구사항

다음 외부 계약은 변경하지 않는다.

- 모든 HTTP URL과 HTTP 메서드
- 요청 파라미터 이름, 필수 여부와 기본값
- 경로 변수 이름
- 응답 데이터와 Thymeleaf 뷰 이름
- `application.yml`의 설정 키와 기본값
- `conf/`의 운영 경로와 내부 구조
- Windows 서비스 설치·실행·제거 스크립트의 사용 방식
- 배포 ZIP 내부의 `bin`, `conf`, `jre`, `lib`, `workspace` 구조

외부 시스템이 Java 클래스의 완전한 클래스명(FQCN)을 직접 참조하지 않으므로 `geomex.sync` 아래의 패키지명은 변경할 수 있다.

## 3. 현재 구조 분석

프로덕션 Java 코드는 47개 파일, 약 7,765줄이다. 기술 계층별 규모는 다음과 같다.

| 기존 패키지 | 파일 수 | 줄 수 |
|---|---:|---:|
| `service` | 12 | 2,354 |
| `web` | 15 | 2,119 |
| `worker` | 6 | 1,922 |
| `repository` | 2 | 487 |
| `scheduler` | 2 | 369 |
| 기타 | 10 | 514 |

기술 계층별 배치는 하나의 기능을 이해할 때 여러 디렉터리를 오가게 한다. 특히 `KrasWorker`, `SettingsController`, `OdsRepository`, `KrasWorkspaceScanner`, `KrasApiClient`, `KrasGmxController`는 크기가 크지만, 이번 작업에서는 내부 책임을 분리하지 않고 관련 기능 패키지로만 이동한다.

현재 테스트는 mapper, 좌표 변환, ODS SQL 및 mock KRAS 적재에 집중되어 있다. 컨트롤러, 서비스, 스케줄러의 외부 계약을 보호하는 테스트는 부족하다.

## 4. 목표 Java 패키지 구조

`SyncApplication`은 Spring 컴포넌트 스캔의 루트로 `geomex.sync`에 유지한다.

```text
src/main/java/geomex/sync/
├─ SyncApplication.java
├─ configuration/
│  ├─ DatabaseConfig.java
│  ├─ KrasGpkiProperties.java
│  ├─ TargetDb.java
│  └─ TargetDbProperties.java
├─ settings/
│  ├─ RuntimeSettingsService.java
│  └─ SettingsController.java
├─ common/
│  ├─ geo/CoordTransformer.java
│  └─ xml/XmlUtil.java
├─ database/
│  ├─ TargetDbService.java
│  ├─ TargetTableNameService.java
│  ├─ DbSetupService.java
│  └─ DbSetupController.java
├─ ods/
│  ├─ OdsRepository.java
│  ├─ OdsTableDdl.java
│  ├─ OdsController.java
│  └─ UsezoneCodeService.java
├─ kras/
│  ├─ KrasApiClient.java
│  ├─ KrasGpkiService.java
│  ├─ KrasFileReader.java
│  ├─ KrasFileWriter.java
│  ├─ KrasWorkspaceScanner.java
│  ├─ KrasFileDownloadService.java
│  ├─ KrasTxtLoaderService.java
│  ├─ KrasCatalogStatusService.java
│  ├─ FileDownloadController.java
│  └─ MockGatewayController.java
├─ gateway/
│  ├─ KorepsApiClient.java
│  ├─ KrasConnController.java
│  ├─ KrasGmxController.java
│  ├─ GatewayPaths.java
│  ├─ ConnRequestLogService.java
│  └─ ConnStatsController.java
├─ synchronization/
│  ├─ model/
│  │  ├─ ColumnDef.java
│  │  ├─ SyncTableDef.java
│  │  └─ SyncHistory.java
│  ├─ TableMapper.java
│  ├─ KrasWorker.java
│  ├─ SyncStatusService.java
│  ├─ SyncExecutionLogService.java
│  ├─ SyncScheduler.java
│  ├─ DynamicScheduleManager.java
│  ├─ SyncController.java
│  └─ ScheduleController.java
└─ monitoring/
   ├─ CatalogFileStatus.java
   ├─ DashboardController.java
   ├─ LogController.java
   ├─ ApiTestController.java
   └─ ApiTestProxyController.java
```

`ApiTestController`와 `ApiTestProxyController`는 현재 관리 UI에서 연결 상태와 API 동작을 확인하는 역할이므로 `monitoring`에 둔다.

### 4.1 배치 원칙

- 기능 소유권을 패키지의 첫 번째 기준으로 사용한다.
- 클래스명과 구현은 유지하고 `package` 선언과 필요한 `import`만 변경한다.
- `OdsTableDdl`은 `OdsRepository`와 같은 `ods` 패키지에 둔다.
- `GatewayPaths`는 이를 사용하는 게이트웨이 컨트롤러와 같은 `gateway` 패키지에 둔다.
- 기존 결합을 해소하기 위한 인터페이스 도입이나 클래스 분리는 하지 않는다.
- 기존 `web`, `service`, `worker`, `repository`, `scheduler`, `model`, `mapper`, `geo`, `util` 패키지는 최종적으로 제거한다.

## 5. 저장소 루트 구조

실행 또는 배포 계약에 참여하는 폴더는 유지하고, 루트에 흩어진 분석 문서만 정리한다.

```text
repository/
├─ src/
├─ conf/
├─ scripts/
├─ bin/
├─ lib/
├─ mock/
├─ api-tests/
├─ docs/
│  ├─ reference/
│  │  ├─ 46870-data-catalog.md
│  │  ├─ lt-c-uzone-plan.md
│  │  └─ kras-geomex-sync-structure.md
│  ├─ reviews/
│  │  └─ improvements.md
│  └─ superpowers/
│     ├─ specs/
│     └─ plans/
├─ AGENTS.md
├─ CLAUDE.md
├─ INSTALL.md
├─ build.gradle
├─ settings.gradle
├─ gradlew
└─ gradlew.bat
```

### 5.1 유지하는 경로

- `conf/`: Java 기본 경로, 테스트 및 배포 ZIP이 직접 참조한다.
- `scripts/`, `bin/`, `lib/`: Windows 서비스와 `jlinkZip`의 계약이다.
- `mock/`: 기존 샘플 및 참조 자료 경로를 보존한다.
- `api-tests/`: 기존 PowerShell 실행 명령을 보존한다.
- `AGENTS.md`, `CLAUDE.md`: 저장소 도구 지침이므로 루트에 둔다.
- `INSTALL.md`: 배포 사용자가 즉시 찾을 수 있도록 루트에 둔다.

### 5.2 이동하는 문서

| 기존 경로 | 목표 경로 |
|---|---|
| `46870_DATA_CATALOG.md` | `docs/reference/46870-data-catalog.md` |
| `lt_c_uzone_plan.txt` | `docs/reference/lt-c-uzone-plan.md` |
| `mock/KRAS_GEOMEX_SYNC_STRUCTURE.md` | `docs/reference/kras-geomex-sync-structure.md` |
| `IMPROVEMENTS.md` | `docs/reviews/improvements.md` |

텍스트 형식인 `lt_c_uzone_plan.txt`는 내용 변경 없이 Markdown 확장자로 옮긴다. 이동된 문서나 코드에서 기존 경로를 참조하면 새 경로로 갱신한다. `mock/`의 데이터 파일은 이동하지 않는다.

## 6. 리소스 구조

`src/main/resources`의 현재 구조는 유지한다.

```text
src/main/resources/
├─ application.yml
├─ logback-spring.xml
├─ templates/
├─ static/css/
└─ mock-gateway/
```

템플릿을 기능 하위 폴더로 이동하면 컨트롤러의 뷰 이름과 템플릿 간 참조가 바뀌므로 호환성 위험에 비해 이점이 작다. `mock-gateway` 역시 클래스패스 경로를 유지한다.

## 7. TDD 및 검증 전략

### 7.1 HTTP 계약 characterization test

패키지 이동 전에 `EndpointContractTest`를 추가하여 Spring MVC 애노테이션에서 다음 항목을 검증한다.

- 컨트롤러 단순 클래스명
- 컨트롤러 기본 경로와 메서드 경로
- HTTP 메서드
- `produces`와 `consumes`
- `@RequestParam` 이름, 필수 여부와 기본값
- `@PathVariable` 이름
- 정적으로 확인 가능한 Thymeleaf 뷰 이름

패키지 완전명은 계약에서 제외한다. JSON/XML 응답 본문은 클래스 구현을 변경하지 않는 이번 작업의 범위에서는 별도 스냅샷으로 복제하지 않는다. 기존 테스트, 컴파일 및 Git diff 검토로 구현 본문이 바뀌지 않았음을 확인한다.

### 7.2 패키지 구조 테스트

ArchUnit의 JUnit 5 지원을 테스트 의존성으로 추가한다. `PackageArchitectureTest`는 다음을 검증한다.

- 각 기존 클래스가 승인된 목표 패키지에 존재한다.
- 컨트롤러 클래스명이 `Controller`로 끝난다.
- `common` 패키지는 기능 패키지에 의존하지 않는다.
- `GatewayPaths`와 `OdsTableDdl`은 public으로 노출되지 않는다.
- 최종 단계에서 이전 기술 계층 패키지에 클래스가 남지 않는다.

기존 코드의 실제 결합을 변경하지 않으므로 새로운 단방향 계층 규칙은 이번 작업에서 강제하지 않는다.

### 7.3 저장소 레이아웃 테스트

`RepositoryLayoutTest`는 다음 경로 및 배포 계약을 검증한다.

- 필수 `conf/kras` XML과 `conf/sql` SQL 파일
- `scripts/run.bat`, `install-service.bat`, `uninstall-service.bat`
- `mock/`과 `api-tests/`
- 새 `docs/reference`와 `docs/reviews` 문서
- `jlinkZip`이 기존 ZIP 대상 경로에 `conf`, scripts의 배치 파일, `bin/nssm.exe`, 애플리케이션 JAR 및 GPKI JAR을 배치하도록 구성되어 있음

## 8. 구현 순서

각 기능 묶음은 Red-Green-Refactor 사이클로 처리한다.

1. 현재 HTTP 계약을 characterization test로 고정하고 통과시킨다.
2. 다음 목표 패키지에 대한 구조 규칙을 추가하여 기존 위치에서 실패함을 확인한다.
3. 해당 기능 묶음의 파일, `package` 선언, import와 테스트 패키지를 이동한다.
4. 컴파일과 전체 테스트가 통과하도록 만든다.
5. 중복 파일, 빈 기존 패키지 및 오래된 참조를 확인한다.
6. 다음 기능 묶음으로 진행한다.

기능 묶음은 결합도가 낮은 순서로 이동한다.

```text
common
→ configuration
→ settings
→ database
→ ods
→ kras
→ gateway
→ synchronization
→ monitoring
→ 문서
```

## 9. 실패 처리와 변경 통제

- 한 기능 묶음이 컴파일 및 테스트를 통과하기 전에 다음 묶음으로 넘어가지 않는다.
- 구조 변경과 무관한 버그나 코드 스타일은 수정하지 않는다.
- 작업 시작 시 존재하는 사용자 변경을 되돌리거나 덮어쓰지 않는다.
- 패키지 이동에 필요한 접근 제한 변경이 발견되면 임의로 public으로 바꾸지 않는다. 같은 기능 패키지 배치를 우선하고, 해결되지 않으면 설계 변경으로 보고 중단한다.
- 문서 이동은 Java 패키지 이동이 모두 검증된 뒤 수행한다.

## 10. 환경 조건

현재 작업 환경에서 `gradlew.bat test`는 `Java not found. Place JDK in project jdk\ folder or set JAVA_HOME.` 오류로 실행되지 않는다. 구현을 시작하려면 프로젝트의 `jdk/` 또는 Java 17 이상을 가리키는 유효한 `JAVA_HOME`이 필요하다.

검증 환경이 준비되지 않은 상태에서는 구현 완료나 테스트 통과를 주장하지 않는다.

## 11. 완료 조건

- 모든 Java 파일이 승인된 기능 패키지에 배치되어 있다.
- 이전 기술 계층 패키지에 Java 클래스가 남아 있지 않다.
- HTTP 계약 characterization test가 통과한다.
- YAML 키와 기본 운영 경로가 변경되지 않았다.
- `conf/`, Windows 서비스 스크립트 및 배포 ZIP 구조가 유지된다.
- 루트 분석 문서가 승인된 `docs/` 위치로 이동되고 모든 내부 참조가 갱신된다.
- 기존 테스트, 패키지 구조 테스트와 저장소 레이아웃 테스트가 모두 통과한다.
- `gradlew.bat bootJar`가 성공한다.
- JLink 환경이 있으면 `gradlew.bat jlinkZip`도 성공한다. 환경이 없으면 미검증 사유를 결과에 명시한다.
- 최종 Git diff에서 기능 로직, 엔드포인트 및 설정값 변경이 없음을 확인한다.

## 12. 후속 작업 후보

다음 항목은 이번 작업에서 변경하지 않는다.

- `KrasWorker`, `SettingsController`, `OdsRepository` 등 대형 클래스의 책임 분리
- 중복되거나 레거시인 동기화 실행 경로 통합
- 런타임 설정 접근 방식 개선
- 서비스 및 컨트롤러 단위 테스트 확대
- 기존 개선 문서에 기록된 기능 버그 수정

각 항목은 별도의 설계, 테스트 및 구현 계획을 거쳐야 한다.
