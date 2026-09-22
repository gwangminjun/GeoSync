# KRAS and GEOSYNC-SYNC-HOME Structure

작성일: 2026-06-25

이 문서는 다음 두 디렉터리를 기준으로 정리한 구조 분석이다.

- `D:\IdeaProjects\local\기존 싱크\kras`
- `D:\IdeaProjects\local\기존 싱크\GEOSYNC-SYNC-HOME`

분석 기준은 로컬 파일, JSP, `web.xml`, 설정 XML, 포함된 `.class` 문자열, `geosync2.jar` 안의 `KrasWorker.java.bak`, 과거 로그다.

## 1. 핵심 결론

`GEOSYNC-SYNC-HOME`의 KRAS 동기화는 `D:\IdeaProjects\local\기존 싱크\kras`의 `/conn/land_info` 같은 API를 직접 호출하지 않는다.

현재 설정상 `GEOSYNC-SYNC-HOME`은 아래 URL을 직접 호출한다.

```text
http://110.20.1.12:8385/conn/estateGateway
```

`kras` 프로젝트도 내부적으로 같은 `estateGateway`를 호출한다. 즉 현재 로컬 코드 기준 구조는 다음과 같다.

```text
GEOSYNC-SYNC-HOME
  -> http://110.20.1.12:8385/conn/estateGateway

kras
  -> http://110.20.1.12:8385/conn/estateGateway
```

따라서 KRAS 동기화에 반드시 켜져 있어야 하는 것은 `estateGateway` 서비스다. 운영상 그 서비스를 `KRAS-KOREPSConn` 또는 `kras`라고 부를 수는 있지만, 로컬의 `kras` 폴더를 단순히 Tomcat에 올린다고 `110.20.1.12:8385/conn/estateGateway`가 생기는 구조는 현재 파일상 확인되지 않는다.

## 2. 전체 호출 구조

```text
[GEOSYNC-SYNC-HOME]
  Java batch/service
  geosync.ext.KrasWorker
      |
      | POST
      | conn_svc_id=KRAS000037, KRAS000038, KRAS000039, KRAS000040...
      | conn_sys_id=...
      | adm_sec_cd=46870
      | gpki_id=...
      v
[estateGateway]
  http://110.20.1.12:8385/conn/estateGateway
      |
      | 실제 KRAS/KOREPS 연계, GPKI 인증/복호화 또는 기관망 연계 처리 추정
      v
[KRAS/KOREPS 원천 서비스]


[kras 웹앱]
  JSP, /conn/*, /svc/*
      |
      | 내부 KrasConn/KorepsConn이 POST
      v
  http://{기관IP}:8385/conn/estateGateway
```

## 3. GEOSYNC-SYNC-HOME 역할

`GEOSYNC-SYNC-HOME`은 KRAS/KAIS 데이터를 수집해서 작업 파일과 PostgreSQL/PostGIS 대상 DB에 적재하는 동기화 배치/서비스 프로젝트다.

주요 역할:

- KRAS 웹서비스에서 SHP/DBF/SHX/TXT 데이터를 다운로드
- 다운로드 파일을 `workspace/kras/{기관코드}` 아래에 저장
- SHP/TXT를 GMX 중간 파일 또는 DB 적재 흐름으로 변환
- ODS 또는 지정 대상 테이블에 적재
- 작업 로그를 `logs/{year}` 아래에 기록
- Windows 서비스 또는 배치 파일로 실행

주요 파일:

```text
GEOSYNC-SYNC-HOME/
  bin/
    goSyncKras.bat
    goKRASKOREPSConn.bat
    startSyncService.bat
  conf/
    sync.properties
    source-nodes.xml
    target-nodes.xml
    workspace-kras.xml
    kras/46870.xml
    kras/base-tables.xml
  lib/
    geosync2.jar
  logs/
  workspace/
    kras/46870/
```

### 3.1 KRAS 설정

`conf/source-nodes.xml`:

```xml
<source type='kras' name='kras46870' epsg='5174'>
    <url>http://110.20.1.12:8385/conn/estateGateway</url>
    <chk_pnu>4687025625111190010</chk_pnu>
</source>
```

의미:

- KRAS source 이름은 `kras46870`
- KRAS 좌표계는 EPSG:5174
- 실제 HTTP 호출 대상은 `110.20.1.12:8385/conn/estateGateway`
- `chk_pnu`는 공시지가 존재 여부 확인에 사용되는 PNU

`conf/workspace-kras.xml`:

```xml
<sync-work>
    <name>kras46870</name>
    <path>CONF_HOME/kras/46870.xml</path>
    <mode>W:4:21:05</mode>
</sync-work>
```

`conf/target-nodes.xml`:

```xml
<target type='GMX' name='kras46870' epsg='5174'>
    <path><![CDATA[WORKSPACE/kras/46870]]></path>
</target>
```

### 3.2 실제 호출 방식

`geosync2.jar` 안의 `KrasWorker.java.bak` 기준으로, KRAS 호출은 `HttpURLConnection`을 사용한 단순 POST다.

대표 호출:

```text
conn_svc_id=KRAS000037
conn_sys_id={kras.conn_sys_id}
adm_sec_cd=46870
gpki_id={kras.gpki.id}
```

서비스 코드 용도:

| 코드 | 용도 |
|---|---|
| `KRAS000037` | 연속주제 레이어 목록 조회 |
| `KRAS000038` | 레이어 파일 다운로드, `file_type=2/3/4`로 SHP/DBF/SHX |
| `KRAS000039` | 공시지가 TXT 다운로드 |
| `KRAS000040` | 토지기본정보 TXT 다운로드 |
| `KRAS000011` | 특정 PNU 공시지가 존재 여부 확인 |

다운로드 흐름:

```text
KrasWorker
  -> getUseZoneLayers()
  -> downloadFile(... file_type=2)
  -> downloadFile(... file_type=3)
  -> downloadFile(... file_type=4)
  -> workspace/kras/46870/*.shp, *.dbf, *.shx 저장
  -> GMX/DB 적재 흐름 수행
```

## 4. kras 프로젝트 역할

`kras`는 JSP/Servlet 2.5 + Jersey 1.x 기반의 레거시 웹앱이다. 소스 `.java`는 없고 컴파일된 `.class`와 JSP, 설정 파일, 라이브러리가 포함되어 있다.

주요 역할:

- KRAS/KOREPS `estateGateway`를 호출하는 웹 중계 API
- `pnu`, `bno` 등을 받아 토지/건축물 관련 XML 응답 제공
- 일부 JSP에서 KRAS 결과 XML 또는 base64 이미지를 직접 출력
- `/check` 아래에 로그인 기반 테스트 UI 제공
- DB pool과 코드 조회용 PostgreSQL 연결 설정 포함

주요 파일:

```text
kras/
  WEB-INF/
    web.xml
    KrasConn.cfg
    KrasConn.servers
    KorepsConn.cfg
    KorepsConn.servers
    KrasGmxConn.cfg
    dbpool.xml
    classes/
    lib/
  check/
  _inc/
  getKRAS*.jsp
  get_kras_*.jsp
```

### 4.1 web.xml 구조

`WEB-INF/web.xml` 기준:

```xml
<servlet-class>geosync.kras.KrasConnCfg</servlet-class>
<load-on-startup>1</load-on-startup>

<servlet-class>geosync.koreps.KorepsConnCfg</servlet-class>
<load-on-startup>1</load-on-startup>

<servlet-name>KrasConnection</servlet-name>
<servlet-class>com.sun.jersey.spi.container.servlet.ServletContainer</servlet-class>
<param-value>geosync.kras.land;geosync.kras.bldg</param-value>
<url-pattern>/conn/*</url-pattern>

<servlet-name>GmxCtrl</servlet-name>
<param-value>geosync.kras.gmx.ctrl</param-value>
<url-pattern>/svc/*</url-pattern>
```

노출되는 주요 API 그룹:

```text
/conn/*
  geosync.kras.land
  geosync.kras.bldg

/svc/*
  geosync.kras.gmx.ctrl
```

### 4.2 /conn/* API

확인된 컨트롤러:

| 분류 | 컨트롤러 | 경로 성격 |
|---|---|---|
| 토지 | `LandInfoCtrl` | `land_info` |
| 토지 | `LandAttrCtrl` | `land_attr` |
| 토지 | `LandBldgCheckCtrl` | `land_bldg_check` |
| 토지 | `LandJigaCtrl` | `land_jiga` |
| 토지 | `LandMovHistCtrl` | `land_mov_hist` |
| 토지 | `LandUsePlanAttrCtrl` | `land_use_plan_attr` |
| 토지 | `LandUsePlanInfoCtrl` | `land_use_plan_info` |
| 토지 | `OwnRgtHistCtrl` | `own_rgt_hist` |
| 토지 | `ReadDecJigaCtrl` | `read_dec_jiga` |
| 토지 | `ShrYmbCtrl` | `shr_ymb` |
| 토지 | `UseZoneCtrl` | `use_zone` |
| 건축물 | `BldgDongInfoCtrl` | `bldg_dong_info` |
| 건축물 | `BldgHdsInfoCtrl` | `bldg_hds_info` |
| 건축물 | `BldgHoInfoCtrl` | `bldg_ho_info` |
| 건축물 | `BldgLedgGenHdsInfoCtrl` | `bldg_ledg_gen_hds_info` |
| 건축물 | `CbldgDfhsInfoCtrl` | `cbldg_dfhs_info` |
| 건축물 | `CbldgHdsInfoCtrl` | `cbldg_hds_info` |
| 건축물 | `HouseInfoCtrl` | `house_info` |

각 API는 대체로 다음 파라미터를 받는다.

- `pnu`
- 일부 건축물 API: `bno`
- 일부 토지 API: `stdmt`
- 토지이용계획: `map_width`, `map_height`, `legend_width`, `legend_height`, `scale`

대부분 `/body` 하위 경로를 별도로 제공한다. 전체 XML wrapper 대신 본문 객체 또는 리스트만 반환하는 용도로 보인다.

### 4.3 /svc/* API

`geosync.kras.gmx.ctrl` 패키지 기반 GMX 조합 API다.

확인된 경로 성격:

```text
/svc/GetLandInfo
/svc/GetJigaInfo
/svc/GetShareInfo
/svc/GetLandHistInfo
/svc/GetOwnerHistInfo
/svc/GetTojiDaejangPrint
/svc/GetTojiDaejangPrint2
/svc/GetUseZoneList
/svc/GetLandUsePlanInfo
/svc/GetBldgInfo
/svc/GetBldgList
/svc/GetDjyrecaptitle
/svc/GetDjytitle
/svc/GetJeonyubldg
/svc/GetDjyexpos
/svc/GetHouseInfo
/svc/LandUsePlanAttr
```

이 계층은 단일 KRAS 응답을 그대로 반환하기보다 여러 토지/건축물 API 결과를 조합해 GMX 쪽 포맷으로 제공하는 성격이다.

### 4.4 kras의 estateGateway 호출

`kras` 자체는 `/conn/estateGateway`를 제공하는 코드가 확인되지 않았다.

대신 내부 `KrasConn.class`, `KorepsConn.class`에 다음 문자열이 들어 있다.

```text
:8385/conn/estateGateway
POST
```

`WEB-INF/KrasConn.servers`:

```text
46870=110.20.1.12
```

따라서 `kras`의 KRAS 호출 URL은 다음 형태로 만들어지는 것으로 보인다.

```text
http://110.20.1.12:8385/conn/estateGateway
```

즉 `kras`는 `estateGateway`의 서버라기보다 `estateGateway`의 클라이언트다.

## 5. GPKI 인증 구조

### 5.1 GEOSYNC-SYNC-HOME

`conf/sync.properties`에는 GPKI 설정 항목이 있지만 현재 주석 처리되어 있다.

```properties
##kras.gpki.id=...
##kras.gpki.lic=...
##kras.cert.cer=...
##kras.cert.key=...
##kras.cert.pwd=...
```

`KrasWorker.java.bak`의 `decrypt()` 흐름:

```java
String gpki = prop.getProperty("kras.gpki.id");
if (StrUtil.isEmpty(gpki)) {
    return stText.getBytes("UTF-8");
}
```

의미:

- `kras.gpki.id`가 비어 있으면 응답을 암호문으로 보지 않고 평문 XML/TXT로 처리한다.
- 값이 있으면 GPKI API로 응답 복호화를 시도한다.
- 요청 자체를 GEOSYNC-SYNC-HOME이 GPKI로 서명/암호화하는 구조는 확인되지 않았다.
- `gpki_id`는 POST 파라미터로 gateway에 전달된다.

따라서 운영상 인증/세션/기관망 처리는 `estateGateway` 또는 그 앞단 연계 프로그램에서 처리했을 가능성이 높다.

### 5.2 kras

`WEB-INF/KrasConn.cfg`:

```properties
gpki_use=false
gpki_id=
gpki_api_conf_path=...
gpki_cer_file=...
gpki_pri_key_file=...
gpki_pass=
```

의미:

- GPKI 설정 항목은 존재한다.
- 현재 설정은 `gpki_use=false`다.
- 이 상태에서는 `kras`도 GPKI를 직접 활성 처리하지 않는 구성이다.
- 실제 인증이 필요하면 `estateGateway` 쪽 또는 별도 연계 프로그램 설정이 중요하다.

## 6. 켜져 있어야 하는 대상

KRAS 동기화 성공 조건:

1. `GEOSYNC-SYNC-HOME` 실행 환경이 `http://110.20.1.12:8385/conn/estateGateway`에 접속 가능해야 한다.
2. `110.20.1.12` 서버에서 8385 포트의 `estateGateway` 서비스가 떠 있어야 한다.
3. 해당 gateway가 `conn_sys_id`, 기관코드, GPKI 설정을 처리할 수 있어야 한다.
4. 대상 DB와 workspace 경로가 정상이어야 한다.

현재 로컬 PC에서 확인한 상태:

```text
현재 PC IPv4: 192.168.0.68
110.20.1.12:8385 TCP 연결 실패
http://110.20.1.12:8385/conn/estateGateway POST 실패
로컬 8385 리스닝 확인 안 됨
```

따라서 현재 PC에서 `kras` 폴더를 켜는 것만으로 `110.20.1.12:8385`가 살아나지는 않는다. `110.20.1.12`가 별도 서버라면 그 서버에서 서비스를 확인해야 한다.

운영자가 말한 "kras를 켜야 한다"는 말은 다음 중 하나일 수 있다.

| 의미 | 판단 |
|---|---|
| 로컬 `D:\IdeaProjects\local\기존 싱크\kras`를 켜야 한다 | 현재 설정상 GEOSYNC-SYNC-HOME 직접 의존은 아님 |
| `110.20.1.12` 서버에 배포된 `kras` 또는 KRAS-KOREPSConn 서비스를 켜야 한다 | 맞을 가능성이 큼 |
| `estateGateway`를 제공하는 Tomcat/Java 서비스를 켜야 한다 | 맞음 |
| `kras` 테스트 UI를 켜야 GEOSYNC-SYNC-HOME이 동작한다 | 현재 구조상 아님 |

## 7. 정상 동작 확인 방법

### 7.1 GEOSYNC-SYNC-HOME 실행 서버에서 확인

```powershell
Test-NetConnection 110.20.1.12 -Port 8385
```

성공 기준:

```text
TcpTestSucceeded : True
```

POST 테스트:

```powershell
Invoke-WebRequest `
  -Uri "http://110.20.1.12:8385/conn/estateGateway" `
  -Method Post `
  -Body "conn_svc_id=KRAS000037&conn_sys_id=HOH4-N9PX-73Y6-B393&adm_sec_cd=46870&gpki_id=null" `
  -UseBasicParsing `
  -TimeoutSec 10
```

성공하면 XML 또는 다운로드 가능한 응답이 와야 한다.

### 7.2 estateGateway 서버에서 확인

`110.20.1.12` 서버에 접속해서 확인:

```cmd
netstat -ano | findstr 8385
```

Java/Tomcat 프로세스 확인:

```cmd
tasklist | findstr /i "java tomcat"
```

Tomcat이면 `server.xml`에서 8385 Connector 확인:

```xml
<Connector port="8385" ... />
```

WAR 또는 웹앱 안에 `/conn/estateGateway` 리소스가 있어야 한다.

### 7.3 kras 로컬 웹앱을 켜는 경우

로컬 `kras`를 Tomcat에 배포하면 확인 가능한 것은 다음 계층이다.

```text
http://localhost:{tomcat-port}/kras/conn/land_info?pnu=...
http://localhost:{tomcat-port}/kras/check/
http://localhost:{tomcat-port}/kras/svc/GetLandInfo?pnu=...
```

하지만 이것은 `GEOSYNC-SYNC-HOME`이 현재 호출하는 `estateGateway`와 다르다.

## 8. 장애 해석

### 8.1 `estateGateway` 접속 실패

증상:

```text
연속주제 목록 다운로드 실패
http://110.20.1.12:8385/conn/estateGateway
```

가능 원인:

- 110.20.1.12 서버가 꺼져 있음
- 8385 포트 서비스 미기동
- 방화벽 또는 네트워크 경로 차단
- URL이 변경됨
- 기관별 gateway IP가 바뀜

조치:

- `Test-NetConnection`으로 포트 확인
- `source-nodes.xml` URL 확인
- gateway 서버에서 Java/Tomcat/서비스 확인

### 8.2 GPKI 관련 실패

가능 원인:

- `gpki_id` 필요 여부가 gateway 설정과 맞지 않음
- gateway 쪽 인증서/개인키 만료 또는 경로 오류
- GEOSYNC-SYNC-HOME에서 복호화를 켰지만 인증서 설정이 없음
- 응답이 암호문인데 `kras.gpki.id`가 비어 있어서 평문 XML로 파싱하려 함

조치:

- 현재 운영이 `gpki_id=null`로 성공한 이력이 있는지 로그 확인
- gateway가 평문 응답을 주는 구조인지 확인
- GEOSYNC-SYNC-HOME 자체 복호화 사용 여부 결정

### 8.3 kras API는 되는데 GEOSYNC-SYNC-HOME은 실패

가능 원인:

- `kras` API는 토지/건축물 조회 API이고, GEOSYNC-SYNC-HOME은 `estateGateway` 파일 다운로드 API를 사용한다.
- 두 기능은 호출 경로와 서비스 코드가 다르다.
- `kras`가 내부적으로 gateway를 호출할 때 성공하더라도 GEOSYNC-SYNC-HOME 서버에서 gateway로 가는 네트워크가 막혀 있을 수 있다.

## 9. 운영 관점 요약

`kras`:

- JSP/Jersey 웹앱
- 사용자가 PNU 기반 조회를 하거나 GMX용 조합 API를 호출하는 용도
- 내부적으로 KRAS/KOREPS `estateGateway`를 호출
- `/conn/estateGateway` 서버 구현은 현재 로컬 파일에서 확인되지 않음

`GEOSYNC-SYNC-HOME`:

- 배치/서비스형 동기화 프로그램
- `KrasWorker`가 `estateGateway`에서 파일을 내려받음
- workspace에 파일 저장 후 DB 적재 흐름 수행
- KRAS 동기화 성공 여부는 `estateGateway` 접속 가능성에 직접 의존

`estateGateway`:

- `GEOSYNC-SYNC-HOME`과 `kras`가 공통으로 바라보는 KRAS/KOREPS 연계 지점
- 현재 설정상 주소는 `110.20.1.12:8385`
- 이 서비스가 실제로 떠 있어야 KRAS 수집이 된다.

