#!/bin/bash

mkdir /root/.docker/
cat /etc/kubelet.config.json /etc/pull-secrets/*.dockerconfigjson | jq -s 'reduce .[] as $x ({}; . * $x)' > /tmp/config.json
if [ "$(ls -A /etc/pull-secrets/)" ]; then
  cat /tmp/config.json /etc/pull-secrets/*.dockercfg | jq -s '{ "auths" : (.[0].auths * (reduce .[1:][] as $x ({}; . * $x))) }' > /root/.docker/config.json
else
  mv /tmp/config.json /root/.docker/config.json
fi
echo $KEYSTORE | base64 -d > /root/keystore.jks
java -jar /root/quarkus-run.jar