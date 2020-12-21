#!/bin/bash

mkdir /root/.docker/
cat /etc/kubelet.config.json /etc/pull-secrets/*.dockerconfigjson | jq -s 'reduce .[] as $x ({}; . * $x)' > /tmp/config.json
cat /tmp/config.json /etc/pull-secrets/*.dockercfg | jq -s '{ "auths" : (.[0].auths * (reduce .[1:][] as $x ({}; . * $x))) }' > /root/.docker/config.json
echo $KEYSTORE | base64 -d > /root/keystore.jks
java -jar /root/quarkus-run.jar