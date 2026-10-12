@echo off
rem Shardlight Client portable start script for Windows.
rem Needs a Java 21 or newer runtime. It is looked for in this order:
rem   1. SHARDLIGHT_JAVA (full path to java.exe or javaw.exe)
rem   2. JAVA_HOME
rem   3. the Java runtimes the official Minecraft Launcher downloads
rem   4. java on the PATH
setlocal EnableExtensions DisableDelayedExpansion
set "HERE=%~dp0"
set "JAVA="

if defined SHARDLIGHT_JAVA call :try "%SHARDLIGHT_JAVA%"
if not defined JAVA if defined JAVA_HOME call :try "%JAVA_HOME%\bin\javaw.exe"
if not defined JAVA (
    for %%R in ("%APPDATA%\.minecraft\runtime" "%LOCALAPPDATA%\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime") do (
        if exist "%%~R" if not defined JAVA (
            for /f "delims=" %%J in ('dir /b /s "%%~R\javaw.exe" 2^>nul') do (
                if not defined JAVA call :try "%%J"
            )
        )
    )
)
if not defined JAVA (
    for /f "delims=" %%J in ('where javaw.exe 2^>nul') do (
        if not defined JAVA call :try "%%J"
    )
)
if not defined JAVA (
    echo Shardlight Client needs Java 21 or newer.
    echo Install one, for example from https://adoptium.net, or set SHARDLIGHT_JAVA to its javaw.exe.
    echo The installer version of Shardlight Client includes its own Java and needs nothing extra.
    pause
    exit /b 1
)

start "Shardlight Client" "%JAVA%" -Xss2m -XX:+UseG1GC -Dprism.lcdtext=false --enable-native-access=ALL-UNNAMED ^
    -cp "%HERE%lib\*;%HERE%lib\javafx-win\*" dev.shardlight.client.app.Main %*
exit /b 0

rem :try PATH - sets JAVA when PATH is a Java runtime of version 21 or newer
:try
set "CAND=%~1"
if not exist "%CAND%" exit /b 0
set "CANDJAVA=%CAND:javaw.exe=java.exe%"
if not exist "%CANDJAVA%" set "CANDJAVA=%CAND%"
set "VER="
for /f "tokens=2 delims==" %%V in ('call "%CANDJAVA%" -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.specification.version"') do set "VER=%%V"
if not defined VER exit /b 0
set "VER=%VER: =%"
for /f "tokens=1 delims=." %%M in ("%VER%") do set "MAJOR=%%M"
if "%MAJOR%"=="1" exit /b 0
if %MAJOR% GEQ 21 set "JAVA=%CAND%"
exit /b 0
