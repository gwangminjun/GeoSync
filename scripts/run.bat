@echo off
set SYNC_HOME=%~dp0..
"%SYNC_HOME%\jre\bin\java.exe" -jar "%SYNC_HOME%\lib\geomex-sync.jar" --spring.config.location=file:"%SYNC_HOME%\conf\application.yml"