# This Dockerfile is intended for use by openshift/ci-operator config files defined
# in openshift/release for v4.x prow based PR CI jobs

FROM quay.io/openshift/origin-jenkins-agent-maven:v4.0 AS builder
WORKDIR /java/src/github.com/openshift/jenkins-client-plugin
COPY . .
USER 0
RUN export PATH=/opt/rh/rh-maven35/root/usr/bin:$PATH && mvn clean package

FROM quay.io/openshift/origin-jenkins:v4.0
RUN rm /opt/openshift/plugins/openshift-client.jpi
COPY --from=builder /java/src/github.com/openshift/jenkins-client-plugin/target/openshift-client.hpi /opt/openshift/plugins
RUN mv /opt/openshift/plugins/openshift-client.hpi /opt/openshift/plugins/openshift-client.jpi
