@echo off
set SYNC_HOME=%~dp0..

echo 서비스를 중지하고 제거합니다...
net stop GeoSyncService >nul 2>&1
"%SYNC_HOME%\bin\nssm.exe" remove GeoSyncService confirm

echo [완료] GeoSyncService 제거
pause
