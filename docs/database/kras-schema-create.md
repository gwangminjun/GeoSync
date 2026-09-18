# KRAS 스키마 신규 생성 SQL

작성일: 2026-09-11

[실행 SQL: kras-schema-create.sql](./kras-schema-create.sql)  
[기준 설계서](../superpowers/specs/2026-09-11-kras-database-sync-design.md)

기존 PostgreSQL DB 안에 `kras` 스키마와 테이블·인덱스·조회 뷰를 생성하는 **최초 구축용 SQL**이다. 별도 데이터베이스를 생성하는 `CREATE DATABASE`는 포함하지 않는다. SQL 파일 전체를 순서대로 실행한다.

## 실행 조건

- PostgreSQL 16, PostGIS가 설치된 데이터베이스를 기준으로 작성했다.
- 실행 계정에는 DB의 스키마 생성 권한과 신규 객체 생성 권한이 필요하다. 이미 빈 `kras` 스키마가 있다면 해당 스키마의 CREATE 권한도 필요하다.
- PostGIS 확장이 없다면 DBA가 먼저 아래 명령을 실행한다. 이미 설치되어 있다면 확장 스키마를 자동 인식한다.

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
```

- 신규 도형 저장 좌표계는 EPSG:5186이다. 원본 도형의 실제 CRS를 확인하고 수집기에서 좌표변환한 후 저장한다.
- 기존 `public`, `ods` 등의 업무 객체나 애플리케이션 설정은 변경하지 않는다.

## 실행 방법

DBeaver/pgAdmin에서는 대상 DB에 연결하여 SQL 파일을 열고 **전체 스크립트 실행**을 사용한다. 오류 발생 시 `ROLLBACK;`으로 실패 트랜잭션을 종료한 뒤 원인을 확인한다.

psql 실행 예:

```powershell
psql -h DB_HOST -p 5432 -U DB_USER -d DB_NAME -v ON_ERROR_STOP=1 -f docs/database/kras-schema-create.sql
```

`DB_HOST`, `DB_USER`, `DB_NAME`은 배포 환경 값으로 바꾼다. 비밀번호는 SQL에 기록하지 않는다.

전체 DDL과 데이터셋 등록은 `BEGIN`/`COMMIT` 한 트랜잭션이다. 중간 실패 시 해당 실행의 생성 결과가 커밋되지 않는다. 스키마 생성 외에는 `IF NOT EXISTS`로 기존 구조 차이를 숨기지 않는다. **같은 SQL을 두 번 실행하면 기존 객체 중복으로 실패한다.** 재실행을 위해 기존 스키마를 삭제하지 말고 이미 적용된 상태를 확인한다. 구조 변경은 후속 마이그레이션으로 작성한다.

## 생성 범위

총 **102개 테이블**: 업무 테이블 39개, 업무별 `stage_*` 39개, 동기화 제어 10개, API 게시 5개, 공간·코드 관리 8개, 기존 공시지가 TXT 보존 1개. 여기에 도메인 3개와 조회 뷰 5개를 생성한다.

| 영역 | 대표 객체 | 역할 |
|---|---|---|
| 필지·대장 | `parcel`, `land_basic`, `land_register`, `land_owner`, `land_share` | PNU 마스터·대장·공유인 |
| 토지·소유연혁 | `land_movement_history`, `land_ownership_history`, `land_change_event` | 원천 연혁 및 기간별 변동 |
| 대지권 | `collective_building`, `collective_unit`, `land_right`, `unit_ownership_history` | 집합건물 대지권·전유부·소유연혁 |
| 건물통합 | `integrated_building`, `building_parcel`, `building_image` | 통합건물·필지관계·도면 |
| 건축물대장 | `building_register`, `building_summary`, `building_title`, `building_unit`, `building_exclusive` 및 자식 | 동·총괄·표제부·호·전유부 |
| 가격·토지특성 | `koreps_land_price`, `house_price`, `final_land_price`, `read_land_price`, `land_attribute` | KOREPS 응답 |
| 토지이용계획 | `land_use_attribute`, `land_use_zone`, `land_use_plan` 및 자식 | 속성·용도지역 적용정보·지도·범례 |
| API 게시 | `api_response`, `api_bundle`, `api_bundle_member`, `api_publication`, `api_coverage` | 정상 XML·버전 집합·커버리지 |
| 동기화 | `sync_dataset`, `sync_policy`, `sync_run`, `sync_item`, `sync_file`, `sync_record`, `sync_reject`, `sync_work`, `sync_checkpoint`, `sync_publication` | 주기·실행·원본·재시도·게시 |
| 공간 | `spatial_layer`, `spatial_feature`, `cadastral_feature`, `usezone_feature`, `spatial_release`, `spatial_release_member`, `spatial_publication`, `usezone_code` | 원본 도형·게시 투영·레이어 집합·코드 |
| 기존 공시지가 | `land_price_file_row` | KRAS000039 전체 TXT의 버전별 행 |
| 검증 영역 | `stage_<업무테이블>` 39개 | 타입 변환 성공 후 업무 반영 전 검사 |

모든 객체는 `kras` 스키마에 생성된다. HWP 11·12절의 미확정 소유권 변동 전용 테이블은 만들지 않는다. 해당 응답은 `sync_record.payload`에 보존하도록 설계했다.

## 조회용 객체

| 뷰 | 게시 기준 |
|---|---|
| `kras.lp_pa_cbnd` | `sync_publication`: dataset=`cadastral_file`, scope=`LAYER:<실제 layer_code>` |
| `kras.lt_c_uzone` | 기관별 `spatial_publication`의 PUBLISHED release와 성공한 레이어 item |
| `kras.anvm_jiga` | `sync_publication`: dataset=`land_price_file`, scope=`ALL` |
| `kras.land_frst_ledg` | 현재 ACTIVE 필지·토지기본 업무행 |
| `kras.api_current_response` | PUBLISHED 상태의 완전한 활성 API bundle |

이 뷰들은 조회용이다. 기존 삭제·재삽입 writer를 그대로 이 뷰에 연결하지 않는다. 신규 배치는 물리 테이블에 적재하고 검증 후 게시 포인터를 바꿔야 한다. API는 요청 시 bundle을 고정하고 해당 구성원을 읽어 기존 XML 응답을 조립한다.

공간 뷰 `uid`는 기존 소비자의 int4 타입을 유지하도록 별도 integer identity를 사용한다. 원천 UID 및 신규 bigint `feature_id`와 구분하며 버전 간 동일한 UID는 보장하지 않는다. 장기 누적 시 integer 한도에 대한 운영 관리가 필요하다. 공시지가 호환 뷰의 `jiga`는 설계에 따라 기존 10자리보다 큰 `numeric(12,0)`을 수용한다. 용도지역 레이어명은 원본 코드 보존을 위해 varchar(80)이며, 짧은 코드가 필요한 기존 소비자는 수집 시 계약에 맞는 값을 지정한다.

## 수집·반영 규칙

1. `sync_run`과 `sync_item`을 먼저 생성하고 원본 파일·레코드를 저장한다. 모든 업무행의 `source_item_id`는 필수다.
2. `sync_record`는 파싱 전/오류를 포함한 원본 추적점이다. 타입 변환된 행을 `stage_*`에 적재한다. stage는 `(item_id,row_no)` PK 외에 업무 FK/UNIQUE/identity/default를 복사하지 않으며, 복사된 업무 컬럼의 NOT NULL도 제거했다. 업무 ID는 스테이징에서 비워둘 수 있다.
3. 정상 검증된 데이터만 부모→자식 순서로 업무 테이블에 반영한다. 전유부·공유인·연혁 등 후보 자연키에는 아직 UNIQUE를 강제하지 않았다. 해당 요청 범위를 식별해 교체하며 키 검증 전 임의 UPSERT를 하지 않는다.
4. 업무행·정상 XML 응답·재조회 큐 완료를 같은 적용 트랜잭션에서 반영한다. `updated_at`은 최초 기본값만 있으므로 이후 UPDATE 때 배치가 직접 갱신한다. 도형 원본과 게시용 투영의 속성·좌표 일치도 배치가 검증한다.
5. 필수 자식·파일·레이어를 검증하고 item/bundle/release의 완료 상태와 활성 포인터를 같은 트랜잭션에서 게시한다. 이미 게시된 응답·구성원·도형은 수정하지 않고 새 버전을 만든다.
6. 체크포인트는 중간 실패 구간이 없는 연속 성공 범위까지만 이동한다. DB CHECK는 수집/반영 순서와 날짜 범위만 보장하고, 누락 구간 여부·주기 실행·재시도·오래된 응답 차단은 실행기가 처리한다.

DDL은 FK·날짜/상태 CHECK·조회 인덱스와 게시 상태 필터를 제공한다. 외부 응답의 성공 코드 검증, bundle 필수 구성원 목록, 레이어 scope 일치, 권한/최신성 정책, 게시 이후 불변성까지 DB 트리거로 자동 실행하지는 않는다. 이 규칙은 배치·API 구현에 포함해야 한다. 원본 정리 시 FK에 걸린 item/file/response를 먼저 지우지 않도록 참조 관계와 보존기간을 확인한다. ON DELETE CASCADE는 사용하지 않았다.

## 초기 데이터셋

실제 코드의 19개 API와 파일·레이어 및 HWP 확장 서비스를 합쳐 33개 데이터셋을 등록한다. 전부 `enabled=false`, `contract_status=UNVERIFIED`로 시작하며, 기관별 정책·계정·cron은 생성하지 않는다. 운영 계약을 검증한 데이터셋만 VERIFIED로 바꾸고 정책을 추가한다. `service_code=NULL`은 제공 문서에 실제 ID가 없어 확인해야 하는 서비스다.

정책에 DAILY/MONTHLY를 등록해도 현재 애플리케이션이 자동으로 이를 읽지는 않는다. 신규 테이블 생성과 프로그램의 DB 조회/배치 전환은 별도 단계다.

## 적용 후 확인

```sql
SELECT table_type, count(*)
FROM information_schema.tables
WHERE table_schema = 'kras'
GROUP BY table_type;
-- BASE TABLE 102, VIEW 5

SELECT contract_status, enabled, count(*)
FROM kras.sync_dataset
GROUP BY contract_status, enabled;
-- UNVERIFIED, false, 33

SELECT * FROM kras.lp_pa_cbnd LIMIT 1;
SELECT * FROM kras.lt_c_uzone LIMIT 1;
SELECT * FROM kras.api_current_response LIMIT 1;
-- 초기 수집/게시 전에는 0건이 정상이다.
```

## 검증 결과

별도 임시 **PostgreSQL 16.14 / PostGIS 3.6.2** DB에서 다음을 확인했다.

- 최종 SQL 전체 실행 성공: 테이블 102개, 뷰 5개, 비활성 데이터셋 33개.
- PNU 구성값 불일치, 다른 기관의 실행·응답 연결, 미완료 item의 SUCCESS 전환, 역전된 체크포인트 차단.
- 업무 ID가 없는 staging 행 적재 가능, 같은 `(item_id,row_no)` 중복 차단, 미확정 공유순번 중복 보존.
- 초안 API bundle/공간 release 및 미완료 지적 item은 조회 뷰에 미노출, 게시 완료 후 노출.
- EPSG:5186 도형에 다른 SRID 저장 시 거부.
- 같은 DDL 재실행은 기존 도메인 중복으로 실패하며 기존 객체는 유지.

검증 데이터는 테스트 트랜잭션에서 롤백했다. 운영 DB에는 접속하거나 적용하지 않았다. 애플리케이션 코드는 변경하지 않았으므로 Gradle 테스트 대신 실제 DB DDL·제약·뷰 검증을 수행했다. 실행 SQL은 계정 생성·권한 부여·기존 데이터 이전을 포함하지 않는다.
