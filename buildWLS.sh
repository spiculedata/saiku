#!/bin/bash

SAIKU_WEBAPP_TARGET_DIR="saiku-webapp-2.5-SNAPSHOT"
export SAIKU_WEBAPP_TARGET_DIR

# standard build
cd saiku-webapp
mvn clean install
cd ..

SAIKU_WEBAPP_TARGET_FULL="saiku-webapp/target/${SAIKU_WEBAPP_TARGET_DIR}/WEB-INF/lib"
export SAIKU_WEBAPP_TARGET_FULL

echo "Removing conflicting libs from: ${SAIKU_WEBAPP_TARGET_FULL}"

# remove conflicting libraries
cd $SAIKU_WEBAPP_TARGET_FULL
rm jaxb-api-2.1.jar
rm jaxb-impl-2.2.3.jar
rm spring-aop-3.0.3.RELEASE.jar
rm spring-security-config-3.0.3.RELEASE.jar
rm spring-asm-3.0.3.RELEASE.jar
rm spring-security-core-3.0.3.RELEASE.jar
rm spring-beans-3.0.3.RELEASE.jar
rm spring-security-ldap-3.0.3.RELEASE.jar
rm spring-context-3.0.3.RELEASE.jar
rm spring-security-taglibs-3.0.3.RELEASE.jar
rm spring-context-support-3.0.3.RELEASE.jar	
rm spring-security-web-3.0.3.RELEASE.jar
rm spring-core-3.0.3.RELEASE.jar
rm spring-test-3.0.3.RELEASE.jar
rm spring-expression-3.0.3.RELEASE.jar
rm spring-tx-3.0.3.RELEASE.jar
rm spring-jdbc-3.0.3.RELEASE.jar
rm spring-web-3.0.3.RELEASE.jar
rm spring-ldap-core-1.3.0.RELEASE.jar
rm spring-webmvc-3.0.3.RELEASE.jar
rm spring-security-acl-3.0.3.RELEASE.jar
rm xml-apis-1.0.b2.jar

# create Saiku WebApp WAR file
cd ../..
jar -cf saiku.war *


