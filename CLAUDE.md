# geomex-sync 개발 가이드

## 배포 ZIP 빌드

배포 ZIP은 반드시 Gradle `jlinkZip` 태스크로 생성한다.

```
.\gradlew.bat jlinkZip
```

출력 경로: `build/distributions/geomex-sync-HOME-1.0.0.zip`

ZIP 구조:
- `bin/` — run.bat, install-service.bat, uninstall-service.bat, nssm.exe
- `conf/` — application.yml, kras/base-tables.xml, sql/*.sql 등
- `jre/` — jlink로 생성한 번들 최소 JRE (jdk/ 폴더 필요)
- `lib/` — geomex-sync.jar, libgpkiapi_jni.jar
- `workspace/` — .gitkeep 파일

`Compress-Archive` 등 수동 방식으로 만들면 `jre/`, `bin/nssm.exe` 등이 누락된다.
