FROM openshift/jenkins-2-centos7:v3.11
USER root
RUN touch /opt/openshift/configuration/plugins.txt
RUN touch /opt/openshift/plugins/openshift-client-plugin.lock
COPY ./jpi /opt/openshift/plugins
RUN /usr/local/bin/install-plugins.sh /opt/openshift/configuration/plugins.txt
