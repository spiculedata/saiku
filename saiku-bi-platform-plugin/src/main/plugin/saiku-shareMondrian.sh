#!/bin/bash

# This script makes saiku use the same mondrian as pentaho. Useful to share cache and resources (including CDC)
#
# Use this on pentaho 4.5 (mondrian 3.4) onwards

cp plugin.spring.xml plugin.spring.xml.bak
sed  's/\(.*datasourceResolverClass.*\)/<!-- \1 -->/; s/\(.*saikuDatasourceProcessor.*\)/<!-- \1 -->/; s/PentahoSecurityAwareConnectionManager" init-method="init"/PentahoSecurityAwareConnectionManager"/; ' plugin.spring.xml.bak > plugin.spring.xml

rm lib/mondrian* lib/olap4j* lib/eigenbase*


echo Done