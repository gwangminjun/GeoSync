@echo off
set SYNC_HOME=%~dp0..
cd /d "%SYNC_HOME%"
set "JAVA_OPTS=-Djava.library.path=%SYNC_HOME%\lib"
"%SYNC_HOME%\jre\bin\java.exe" %JAVA_OPTS% -jar "%SYNC_HOME%\lib\geosync.jar" --spring.config.location=file:"%SYNC_HOME%\conf\application.yml"
