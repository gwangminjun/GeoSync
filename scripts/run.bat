@echo off
set SYNC_HOME=%~dp0..

if "%DB_PASSWORD%"=="" (
    echo [ERROR] DB_PASSWORD 환경변수를 설정하세요.
    pause & exit /b 1
)

echo [수동 실행] GeoSync 시작...
"%SYNC_HOME%\jre\bin\java.exe" ^
    --spring.config.location=file:"%SYNC_HOME%\conf\application.yml" ^
    -jar "%SYNC_HOME%\lib\geomex-sync.jar"

pause
