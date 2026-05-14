@echo off
rem Saiku launcher wrapper (Windows).
rem
rem Locates the saiku-*.jar shipped alongside this script and forwards every
rem CLI argument to Saiku's Picocli entry point. The first invocation seeds
rem saiku-home next to this script and runs the H2 FoodMart bootstrap (~30 s,
rem ~200 MB written under saiku-home\data\). Subsequent runs reuse it.

setlocal
set "SCRIPT_DIR=%~dp0"
set "JAR="
for %%F in ("%SCRIPT_DIR%saiku-*.jar") do (
    if not defined JAR set "JAR=%%~fF"
)

if not defined JAR (
    echo error: no saiku-*.jar found in "%SCRIPT_DIR%" 1>&2
    exit /b 1
)

java -jar "%JAR%" serve --home "%SCRIPT_DIR%saiku-home" %*
endlocal
