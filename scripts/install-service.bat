@echo off
setlocal EnableDelayedExpansion
set SYNC_HOME=%~dp0..

echo ============================================================
echo  GeoSync Service Install
echo  SYNC_HOME: %SYNC_HOME%
echo ============================================================

sc query GeoSyncService >nul 2>&1
if !errorlevel! == 0 (
    echo Removing existing service...
    net stop GeoSyncService >nul 2>&1
    "%SYNC_HOME%\bin\nssm.exe" remove GeoSyncService confirm
)

echo Installing service...
"%SYNC_HOME%\bin\nssm.exe" install GeoSyncService "%SYNC_HOME%\jre\bin\java.exe"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService AppParameters "-Djava.library.path=%SYNC_HOME%\lib -jar %SYNC_HOME%\lib\geosync.jar --spring.config.location=file:%SYNC_HOME%\conf\application.yml"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService AppDirectory "%SYNC_HOME%"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService DisplayName "GeoSync Spatial Data Service"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService Description "KRAS/KAIS Sync Service"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService Start SERVICE_AUTO_START
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService AppStdout "%SYNC_HOME%\logs\service-out.log"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService AppStderr "%SYNC_HOME%\logs\service-err.log"
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService AppRotateFiles 1
"%SYNC_HOME%\bin\nssm.exe" set GeoSyncService AppRotateBytes 10485760

echo Starting service...
net start GeoSyncService

echo.
echo [Done] GeoSyncService installed and started.
pause