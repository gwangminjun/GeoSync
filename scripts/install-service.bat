@echo off
setlocal EnableDelayedExpansion
set SYNC_HOME=%~dp0..

echo ============================================================
echo  GeoSync Service 설치
echo  SYNC_HOME: %SYNC_HOME%
echo ============================================================

:: DB_PASSWORD 환경변수 확인
if "%DB_PASSWORD%"=="" (
    echo.
    echo [ERROR] DB_PASSWORD 환경변수가 설정되지 않았습니다.
    echo 먼저 아래 명령을 실행하세요 (관리자 권한):
    echo   setx DB_PASSWORD "your_password" /m
    echo.
    pause
    exit /b 1
)

:: 기존 서비스 중지 및 제거
sc query GeoSyncService >nul 2>&1
if !errorlevel! == 0 (
    echo 기존 서비스를 제거합니다...
    net stop GeoSyncService >nul 2>&1
    "%SYNC_HOME%\bin\nssm.exe" remove GeoSyncService confirm
)

:: 서비스 등록
echo 서비스를 등록합니다...
"%SYNC_HOME%\bin\nssm.exe" install GeoSyncService "%SYNC_HOME%\jre\bin\java.exe"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService AppParameters ^
    --spring.config.location=file:"%SYNC_HOME%\conf\application.yml" ^
    -jar "%SYNC_HOME%\lib\geomex-sync.jar"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService AppDirectory "%SYNC_HOME%"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService AppEnvironmentExtra "DB_PASSWORD=%DB_PASSWORD%"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService DisplayName "GeoSync Spatial Data Service"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService Description "KRAS/KAIS 공간정보 동기화 서비스"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService Start SERVICE_AUTO_START
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService AppStdout "%SYNC_HOME%\logs\service-out.log"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService AppStderr "%SYNC_HOME%\logs\service-err.log"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService AppRotateFiles 1
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService AppRotateBytes 10485760

:: 서비스 시작
echo 서비스를 시작합니다...
net start GeoSyncService

echo.
echo [완료] GeoSyncService 설치 및 시작
pause
