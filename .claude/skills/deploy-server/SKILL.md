---
name: deploy-server
description: "GeoSync를 Windows 운영 서버(GeoSyncService)에 신규 설치/재배포한다. ZIP 빌드 → 서버 복사 → 서비스 설치 → 설정 → 확인 순서. 실제 서비스 설치·재시작을 수행하므로 사용자가 /deploy-server로 직접 호출할 때만 실행한다."
disable-model-invocation: true
---

# 배포: GeoSync 운영 서버 설치/재배포

상세 절차는 [INSTALL.md](../../../INSTALL.md)에 10단계 체크리스트로 정리되어 있다. 이 스킬은 그 절차를 실행할 때 지킬 순서와 확인 지점만 요약한다. 각 단계는 성공을 확인한 뒤 다음 단계로 넘어가고, 실패하면 즉시 멈추고 사용자에게 보고한다.

1. **빌드 (개발 PC)**: `.\gradlew.bat jlinkZip` → `build\distributions\geosync-HOME-1.0.0.zip` 생성 확인 (~90MB, `jdk/` 폴더 필요)
2. **서버 복사**: ZIP을 서버로 옮기고 `C:\geosync\`에 압축 해제 (INSTALL.md Step 1~2)
3. **NSSM 준비**: `bin\nssm.exe` 존재 확인 (INSTALL.md Step 3)
4. **설정**: `conf\application.yml`에 실제 DB 비밀번호, KRAS URL/conn-sys-id 입력 — 이 파일은 서버에서 직접 편집한다 (INSTALL.md Step 4~6)
5. **서비스 설치**: 관리자 권한 CMD에서 `bin\install-service.bat` 실행, `GeoSyncService` 등록·실행 중 상태 확인 (INSTALL.md Step 7)
6. **확인**: 웹 UI(`http://서버IP:18080/`), `logs\geosync.log` 기동 로그, KRAS/KAIS 수동 실행 테스트 (INSTALL.md Step 8~10)
7. **롤백 (실패 시)**: `bin\uninstall-service.bat`로 서비스 제거 후, 이전 버전 ZIP으로 Step 2부터 재시도. `conf\application.yml`은 교체 전 백업해둔다.

prod 서버의 서비스 설치·재시작(Step 7) 직전에는 반드시 사용자 확인을 받는다.
