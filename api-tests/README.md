# KRAS API Test Assets

이 폴더는 기존 코드와 설정에서 확인한 API를 바로 테스트하기 위한 파일이다.

## Postman

`kras-api.postman_collection.json`을 Postman에 import한 뒤 변수만 맞춘다.

- `gatewayBaseUrl`: `http://110.20.1.12:8385`
- `krasBaseUrl`: 로컬 또는 배포된 `kras` 웹앱 주소
- `connSysId`: `GEOSYNC-SYNC-HOME/conf/sync.properties`의 `kras.conn_sys_id`
- `admSecCd`: 현재 설정 기준 `46870`
- `gpkiId`: 현재 운영 로그 기준 `null`
- `pnu`: 테스트 PNU
- `layerCd`: 예: `LSMD_CONT_LDREG`, `LSMD_CONT_UQ121`

## PowerShell

목록 조회와 PNU 체크만:

```powershell
.\api-tests\Test-KrasGateway.ps1
```

레이어 SHP/DBF/SHX까지 저장:

```powershell
.\api-tests\Test-KrasGateway.ps1 -DownloadLayerFiles -LayerCd LSMD_CONT_LDREG
```

TXT 대용량 다운로드까지 포함:

```powershell
.\api-tests\Test-KrasGateway.ps1 -DownloadLayerFiles -IncludeLargeTextDownloads
```

## 확인된 estateGateway 서비스

- `KRAS000037`: 연속주제 레이어 목록 조회
- `KRAS000038`: 레이어 파일 다운로드, `file_type=2/3/4`는 SHP/DBF/SHX
- `KRAS000039`: 공시지가 TXT 다운로드
- `KRAS000040`: 토지기본정보 TXT 다운로드
- `KRAS000011`: 특정 PNU 공시지가 존재 여부 확인

현재 설정에서는 GPKI 직접 인증값이 비활성이고, 요청 파라미터의 `conn_sys_id`가 핵심 식별값이다.
