# This Dockerfile is intended for use by openshift/ci-operator config files defined
# in openshift/release for v4.x prow based PR CI jobs

FROM quay.io/openshift/origin-jenkins-agent-maven:4.11.0 AS builder
WORKDIR /java/src/github.com/openshift/jenkins-client-plugin
COPY . .
USER 0
# We need a newer maven version as the RHEL package is still on 3.6.2
RUN curl -L -o maven.tar.gz https://dlcdn.apache.org/maven/maven-3/3.8.8/binaries/apache-maven-3.8.8-bin.tar.gz && \
	mkdir maven && \
	tar -xvzf maven.tar.gz -C maven --strip-component 1
# Use the downloaded version of maven to build the package
RUN ./maven/bin/mvn --version && \
	./maven/bin/mvn clean package

FROM registry.redhat.io/ocp-tools-4/jenkins-rhel8:v4.12.0
RUN rm /opt/openshift/plugins/openshift-client.jpi
COPY --from=builder /java/src/github.com/openshift/jenkins-client-plugin/target/openshift-client.hpi /opt/openshift/plugins
RUN mv /opt/openshift/plugins/openshift-client.hpi /opt/openshift/plugins/openshift-client.jpi
COPY --from=builder /java/src/github.com/openshift/jenkins-client-plugin/PR-Testing/download-dependencies.sh /usr/local/bin
RUN /usr/local/bin/download-dependencies.sh
