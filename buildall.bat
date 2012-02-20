cd saiku-core
call mvn clean install
cd ..
cd saiku-webapp
call mvn clean install
cd ..
call git submodule init
call git submodule update
cd saiku-ui
call git checkout master
call git pull
call mvn clean package install:install-file -Dfile=target/saiku-ui-2.3-SNAPSHOT.war  -DgroupId=org.saiku -DartifactId=saiku-ui -Dversion=2.3-SNAPSHOT -Dpackaging=war
cd ../saiku-server
call mvn clean package
cd ../saiku-bi-platform-plugin
call mvn clean package
