---
paths:
  - "src/main/java/**/*.java"
---

# 백엔드(Java) 규칙

- 패키지 경계를 지킨다: `config`, `database`, `gateway`, `kras`, `monitoring`, `ods`, `settings`, `synchronization` 등 기존 책임 경계를 넘어 다른 패키지의 내부 클래스를 직접 참조하지 않는다.
- 새 의존성은 생성자 주입으로 추가한다. 필드 주입(`@Autowired` on field)을 새로 추가하지 않는다.
- 환경별 값(DB 접속정보, KRAS 접속정보 등)은 Java 코드에 하드코딩하지 않고 `application.yml` 또는 `conf/`로 뺀다.
- KRAS 응답에 개인정보(소유자명·주소·주민등록번호 등)가 포함될 수 있는 코드를 건드릴 때는 `KrasVerificationEvidenceService`의 `PII_TAGS`/`RRN_PATTERN` 마스킹을 거치지 않은 원문을 로그·DB에 그대로 남기지 않는다.
- 테스트는 `*Test` 접미사, 설명적인 메서드명(`loadsKrasBaseTables` 형태)을 쓴다. mapper·좌표변환·SQL 생성·설정 로딩 관련 변경은 `gradlew.bat test`로 확인한다.
