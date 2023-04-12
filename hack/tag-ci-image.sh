#! /bin/bash
#
# Copyright © 2016 Red Hat, Inc. (https://www.redhat.com)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#


echo "Jenkins image from CI pipeline:jenkins: ${JENKINS_IMAGE}"
if [[ -z ${JENKINS_IMAGE} ]]; then
    echo "No jenkins image env var found, not overriding jenkins imagestream for e2e test of jenkins-client-plugin."
else
    echo "Tagging the CI generated Jenkins image ${JENKINS_IMAGE} from pipeline:jenkins into the test cluster's jenkins imagestream in the openshift namespace"
    echo "Current contents of the jenkins imagestream in the openshift namespace"
    oc describe is jenkins -n openshift
    echo "Tagging ${JENKINS_IMAGE} into the jenkins imagestream in the openshift namespace"
    oc tag --source=docker ${JENKINS_IMAGE} openshift/jenkins:2
    # give some time for the image import to finish; watching from the CLI is non-trivial
    sleep 30
    echo "New contents of the jenkins imagestream in the openshift namespace"
    oc describe is jenkins -n openshift
fi
echo "Jenkins Agent Base image from CI pipeline:jenkins-agent-base: ${JENKINS_AGENT_BASE_IMAGE}"
if [[ -z ${JENKINS_AGENT_BASE_IMAGE} ]]; then
    echo "No jenkins agent base image env var found, not overriding jenkins-agent-base imagestream for e2e test of jenkins-client-plugin."
else
    echo "Tagging the CI generated Jenkins Agent Base image ${JENKINS_AGENT_BASE_IMAGE} from pipeline:jenkins-agent-base into the test cluster's jenkins-agent-base imagestream in the openshift namespace"
    echo "Current contents of the jenkins-agent-base imagestream in the openshift namespace"
    oc describe is jenkins-agent-base -n openshift
    echo "Tagging ${JENKINS_AGENT_BASE_IMAGE} into the jenkins-agent-base imagestream in the openshift namespace"
    oc tag --source=docker ${JENKINS_AGENT_BASE_IMAGE} openshift/jenkins-agent-base:latest
    # give some time for the image import to finish; watching from the CLI is non-trivial
    sleep 30
    echo "New contents of the jenkins imagestream in the openshift namespace"
    oc describe is jenkins-agent-base -n openshift
fi
