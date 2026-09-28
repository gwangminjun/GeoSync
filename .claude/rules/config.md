---
paths:
  - "conf/**"
  - "src/main/resources/**"
---

# 설정/conf 규칙

- `src/main/resources/application.yml`은 Git에 커밋되는 템플릿이다. `${VAR:기본값}` 형식을 유지하고, 실제 운영 비밀번호·접속정보를 기본값으로 새로 채워 넣지 않는다 (기존에 남아있는 기본값은 별개 문제이니 이 작업과 무관하면 건드리지 않는다).
- `conf/application.yml`은 실제 운영 DB 비밀번호가 평문으로 들어있는 배포용 파일이며 `.gitignore` 대상이다. 이 파일 수정은 `.claude/hooks`에서 차단되므로, 값 변경이 필요하면 사용자에게 직접 수정하도록 안내한다.
- `conf/kras/base-tables.xml`, `conf/kais/base-tables.xml`은 KRAS/KAIS 동기화 매핑 정의다. 컬럼 추가·변경 시 대응하는 `*_old.xml`과의 차이를 설명 가능한 상태로 유지한다.
- `conf/sql/*.sql`은 운영 DB에 직접 실행되는 DDL/DML이다. `DROP`/`TRUNCATE` 포함 스크립트는 `.claude/hooks`에서 차단되므로 실행 전 사용자 확인을 받는다.
