#!/bin/bash

if [ ! -f "Dockerfile-jenkins-test-new-plugin" ]; then
    echo "This command must be run from the PR-Testing directory"
    exit 1
fi

HPI="../target/openshift-client.hpi"

if [ ! -f "$HPI" ]; then
    echo "Unable to find HPI artifact (have you run mvn yet?): $HPI"
    exit 1
fi

mkdir -p jpi

cp -f "$HPI" jpi/openshift-client.jpi
if [ "$?" != "0" ]; then
    echo "Unable to copy $HPI"
    exit 1
fi

docker build -f ./Dockerfile-jenkins-test-new-plugin -t openshift/jenkins-client-plugin-snapshot-test:latest .
if [ "$?" != "0" ]; then
    echo "Error building Jenkins snapshot image for plugin testing"
    exit 1
fi

echo "Success. Remember to set USE_SNAPSHOT_JENKINS_IMAGE=1 when running origin extended tests."
