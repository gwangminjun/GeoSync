# GeoSync 실서버 설치 확인 문서

> 작성일: 2026-06-23  
> 대상 서버: Windows Server 2012 이상  
> 서비스명: GeoSyncService  
> 기관코드: 46870 (완도군)

---

## 사전 준비 체크리스트

### 개발 PC (ZIP 빌드)

- [ ] `D:\IdeaProjects\local\geomex-sync\jdk\` 폴더에 JDK 17 존재 확인
- [ ] `gradlew.bat jlinkZip` 실행
- [ ] `build\distributions\geomex-sync-HOME-1.0.0.zip` 생성 확인 (~90MB)

---

## 설치 절차

### Step 1. 서버 OS 버전 확인

서버에서 CMD 실행:
```
wmic os get Caption
```

| 결과 | 비고 |
|------|------|
| `Windows Server 2012 R2` | 정상 (Java 17 지원) |
| `Windows Server 2012` (비 R2) | Java 17 미지원 — 담당자 확인 필요 |

- [ ] OS 버전 확인 완료: `________________________`

---

### Step 2. ZIP 서버 복사 및 압축 해제

```
설치 경로: C:\geomex-sync\
```

압축 해제 후 폴더 구조 확인:
- [ ] `C:\geomex-sync\bin\install-service.bat` 존재
- [ ] `C:\geomex-sync\lib\geomex-sync.jar` 존재
- [ ] `C:\geomex-sync\jre\bin\java.exe` 존재 (번들 JRE)
- [ ] `C:\geomex-sync\conf\kras\base-tables.xml` 존재
- [ ] `C:\geomex-sync\conf\kais\base-tables.xml` 존재
- [ ] `C:\geomex-sync\KAIS_WORK\` 폴더 존재

---

### Step 3. NSSM 준비

1. `https://nssm.cc/download` 에서 `nssm-2.24.zip` 다운로드
2. `nssm-2.24\win64\nssm.exe` 파일 복사

- [ ] `C:\geomex-sync\bin\nssm.exe` 존재 확인

---

### Step 4. `conf\application.yml` 편집

```yaml
spring:
  datasource:
    url: jdbc:postgresql://110.20.1.218:1990/ygspdb
    username: postgres
    password: 실제비밀번호               # ← 여기에 직접 입력

kras:
  url: http://110.20.1.12:8385/conn/estateGateway
  conn-sys-id: HOH4-N9PX-73Y6-B393
  chk-pnu: 4687025625111190010
  schedule: 0 30 4 * * *             # 매일 04:30

kais:
  work-dir: C:\geomex-sync\KAIS_WORK
  schedule: 0 30 3 * * *             # 매일 03:30
```

- [ ] DB URL 확인: `jdbc:postgresql://110.20.1.218:1990/ygspdb`
- [ ] KRAS URL 확인: `http://110.20.1.12:8385/conn/estateGateway`
- [ ] KAIS work-dir 절대경로 확인
- [ ] `password:` 항목에 실제 비밀번호 입력

---

### Step 5. DB 비밀번호 설정

`conf\application.yml` 파일에서 `password:` 항목에 직접 입력:

```yaml
spring:
  datasource:
    password: 실제비밀번호
```

또는 서비스 설치 후 웹 UI → 설정 페이지에서 입력 후 저장 → 서비스 재시작.

- [ ] `conf\application.yml` 비밀번호 입력 확인

---

### Step 6. DB 연결 사전 확인

서버에서 PostgreSQL 접속 가능 여부 확인:
```bat
telnet 110.20.1.218 1990
```
또는
```bat
Test-NetConnection -ComputerName 110.20.1.218 -Port 1990
```

- [ ] DB 서버 포트 연결 가능 확인

---

### Step 7. 서비스 설치

**관리자 CMD에서 실행:**
```bat
cd C:\geomex-sync
bin\install-service.bat
```

정상 출력 확인:
```
GeoSync Service 설치
...
[완료] GeoSyncService 설치 및 시작
```

- [ ] `GeoSyncService` 서비스 등록 확인 (`services.msc`)
- [ ] 서비스 상태: **실행 중**
- [ ] 시작 유형: **자동**

---

### Step 8. 웹 UI 접속 확인

```
http://서버IP:18080/
```

- [ ] 대시보드 페이지 로딩 확인
- [ ] 로그 페이지(`/logs`) 접속 확인
- [ ] 설정 페이지(`/settings`) 접속 확인

---

### Step 9. 로그 정상 확인

```
C:\geomex-sync\logs\geomex-sync.log
```

서비스 기동 직후 아래 로그 확인:
```
[INFO] geomex.sync.SyncApplication - Started SyncApplication in ...
```

- [ ] 기동 로그 확인
- [ ] `ERROR` 레벨 로그 없음 확인

---

### Step 10. 첫 수동 동기화 테스트

웹 UI(`http://서버IP:18080/`) 접속 후:

1. **KRAS 수동 실행** 버튼 클릭
2. 로그 페이지에서 진행 상황 확인
3. `[KRAS] 동기화 완료` 로그 확인

- [ ] KRAS 수동 실행 성공
- [ ] KAIS 수동 실행 성공 (KAIS_WORK에 SHP 파일 투입 후)

---

## 스케줄 확인

| 시각 | 작업 | 설정값 |
|------|------|--------|
| 매일 04:30 | KRAS 동기화 | `kras.schedule=0 30 4 * * *` |
| 매일 03:30 | KAIS 동기화 | `kais.schedule=0 30 3 * * *` |

- [ ] 익일 04:30 이후 자동 실행 이력 확인

---

## 문제 발생 시

### 서비스가 시작되지 않는 경우
```
C:\geomex-sync\logs\service-err.log   ← NSSM 오류 로그
C:\geomex-sync\logs\geomex-sync.log   ← 앱 로그
```

### DB 연결 실패
- `conf\application.yml` 의 `password:` 값 재확인
- 서비스 재시작: `net stop GeoSyncService && net start GeoSyncService`

### 포트 충돌 (8080)
`conf\application.yml` 에 추가:
```yaml
server:
  port: 9090
```

### 서비스 제거
```bat
cd C:\geomex-sync
bin\uninstall-service.bat
```

---

## 설치 완료 서명

| 항목 | 내용 |
|------|------|
| 설치 일시 | |
| 설치 경로 | `C:\geomex-sync\` |
| 서버 OS | |
| 담당자 | |
| 비고 | |
