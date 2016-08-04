@echo off
setlocal

call "%~dp0\set-java.bat"

cd tomcat\bin
set CATALINA_HOME=%~dp0tomcat
set JAVA_HOME=%_JAVA_HOME%
shutdown.bat
endlocal
exit
