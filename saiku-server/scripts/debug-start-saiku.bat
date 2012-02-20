@echo off
setlocal

call "%~dp0\set-java.bat"


cd tomcat\bin
set CATALINA_HOME=%~dp0tomcat
set CATALINA_OPTS=-Xms512m -Xmx768m -XX:MaxPermSize=256m -Dfile.encoding=UTF-8  -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8044 -Dorg.apache.tomcat.util.buf.UDecoder.ALLOW_ENCODED_SLASH=true 
set JAVA_HOME=%_JAVA_HOME%
call startup
:quit
endlocal
