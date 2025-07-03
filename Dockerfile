# This Dockerfile is intended for use by openshift/ci-operator config files defined
# in openshift/release for v4.x prow based PR CI jobs

FROM registry.access.redhat.com/ubi9/openjdk-21:1.20 AS builder
WORKDIR /java/src/github.com/openshift/jenkins-client-plugin
COPY . .
USER 0
# We need a newer maven version as the RHEL package is still on 3.8.5
RUN microdnf -y install gzip && \
	curl -L -o maven.tar.gz https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz && \
	mkdir maven && \
	tar -xvzf maven.tar.gz -C maven --strip-component 1 && \
	# Use the downloaded version of maven to build the package
	./maven/bin/mvn --version && \
	./maven/bin/mvn clean package

FROM registry.redhat.io/ocp-tools-4/jenkins-rhel9:v4.17.0
RUN rm /opt/openshift/plugins/openshift-client.jpi
COPY --from=builder /java/src/github.com/openshift/jenkins-client-plugin/target/openshift-client.hpi /opt/openshift/plugins
RUN mv /opt/openshift/plugins/openshift-client.hpi /opt/openshift/plugins/openshift-client.jpi
