@echo off
setlocal

set DIRNAME=%~dp0
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set DEFAULT_JVM_OPTS=-Xmx64m -Xms64m
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if exist "%APP_HOME%jdk\bin\java.exe" (
    set JAVA_HOME=%APP_HOME%jdk
    set JAVA_EXE=%APP_HOME%jdk\bin\java.exe
    goto execute
)

if defined JAVA_HOME (
    set JAVA_HOME=%JAVA_HOME:"=%
    set JAVA_EXE=%JAVA_HOME%\bin\java.exe
    if exist "%JAVA_EXE%" goto execute
)

set JAVA_EXE=java.exe
where java.exe >nul 2>&1
if %ERRORLEVEL% == 0 goto execute

echo ERROR: Java not found. Place JDK in project jdk\ folder or set JAVA_HOME.
exit /b 1

:execute
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*