# geosync 개발 가이드

GeoSync는 지자체 지적·용도지역 데이터(KRAS/KAIS)를 운영 PostgreSQL/PostGIS DB로 동기화하는 Java 17 + Spring Boot 웹 서비스다. Windows 서버에 Windows 서비스(NSSM)로 설치되어 매일 스케줄 동기화를 실행한다.

## 주요 명령어

| 목적 | 명령어 |
|---|---|
| 전체 테스트 | `.\gradlew.bat test` |
| 로컬 실행 | `.\gradlew.bat bootRun` |
| JAR 빌드 | `.\gradlew.bat bootJar` → `build/libs/geosync.jar` |
| 배포 ZIP 빌드 | `.\gradlew.bat jlinkZip` → `build/distributions/geosync-HOME-1.0.0.zip` (jlink 최소 JRE 포함, `jdk/` 폴더 필요) |

`jlinkZip`은 `bin/`(run.bat·install-service.bat·uninstall-service.bat·nssm.exe), `conf/`, `jre/`, `lib/`(geosync.jar·libgpkiapi_jni.jar), `workspace/`를 묶는다. `Compress-Archive` 등 수동 방식은 `jre/`, `bin/nssm.exe`가 누락되므로 쓰지 않는다.

## 핵심 구조

- `src/main/java/geosync/kras/`: KRAS(부동산행정정보) API 연계 — GPKI 인증, XML/TXT 파싱, mapper별 테이블 적재
- `src/main/java/geosync/synchronization/`: 동기화 스케줄러·실행 이력·상태 조회
- `conf/kras/base-tables.xml`, `conf/kais/base-tables.xml`: 소스-타겟 컬럼 매핑 정의 (코드가 아니라 이 XML로 동기화 대상을 바꾼다)
- `conf/application.yml`: 서버별 실제 운영 설정 (DB 비밀번호 포함, git 추적 안 됨) — `src/main/resources/application.yml`은 `${VAR:기본값}` 템플릿
- `api-tests/`: KRAS 게이트웨이 수동 테스트용 Postman 컬렉션 + PowerShell 스크립트
- `INSTALL.md`: 운영 서버 신규 설치 체크리스트 (실행 절차는 `/deploy-server` 스킬 참고)

## 설정 안내

- 경로별 규칙: `.claude/rules/` — 해당 경로 파일 작업 시 자동 로드
- 서버 설치/재배포 절차: `/deploy-server`로만 실행 (부작용이 있어 자동 호출되지 않음)
- `conf/application.yml`, GPKI 키/인증서, install/uninstall-service.bat 수정은 `.claude/hooks`에서 차단된다. 차단되면 우회하지 말고 사용자에게 직접 수정을 요청한다
- 로그 분석, KRAS 검증 근거 검토, base-tables.xml 신구 대조 등 출력이 큰 작업은 `.claude/agents/`의 서브에이전트에 위임한다
- 모듈 추가, 디렉터리 이동·이름 변경, 빌드·배포 명령 변경을 한 작업에서는 마지막에 `.claude/rules`의 paths와 이 파일의 명령어 갱신이 필요한지 보고한다
