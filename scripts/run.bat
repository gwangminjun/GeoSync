@echo off
set SYNC_HOME=%~dp0..
set JAVA_OPTS=
if exist "%SYNC_HOME%\gpki\native" set "JAVA_OPTS=-Djava.library.path=%SYNC_HOME%\gpki\native"
"%SYNC_HOME%\jre\bin\java.exe" %JAVA_OPTS% -jar "%SYNC_HOME%\lib\geomex-sync.jar" --spring.config.location=file:"%SYNC_HOME%\conf\application.yml"
