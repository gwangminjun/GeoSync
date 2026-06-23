@echo off
set SYNC_HOME=%~dp0..

echo Stopping and removing service...
net stop GeoSyncService >nul 2>&1
"%SYNC_HOME%\bin\nssm.exe" remove GeoSyncService confirm

echo [Done] GeoSyncService removed.
pause