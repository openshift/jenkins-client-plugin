package com.openshift.jenkins.plugins;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.io.Serializable;

public class ClusterConfig extends AbstractDescribableImpl<ClusterConfig> implements Serializable {

    // Human readable name for cluster. Used in drop down lists.
    private String name;

    // API server URL for the cluster.
    private String serverUrl;

    private String serverCertificateAuthority;

    private boolean skipTlsVerify;

    // If this cluster is reference, what project to assume, if any.
    private String defaultProject;

    private String credentialsId;

    @DataBoundConstructor
    public ClusterConfig(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    @DataBoundSetter
    public void setServerUrl(String serverUrl) {
        this.serverUrl = Util.fixEmptyAndTrim(serverUrl);
    }

    public String getServerCertificateAuthority() {
        return serverCertificateAuthority;
    }

    @DataBoundSetter
    public void setServerCertificateAuthority(String serverCertificateAuthority) {
        this.serverCertificateAuthority = Util.fixEmptyAndTrim(serverCertificateAuthority);
    }

    public boolean isSkipTlsVerify() {
        return skipTlsVerify;
    }

    @DataBoundSetter
    public void setSkipTlsVerify(boolean skipTlsVerify) {
        this.skipTlsVerify = skipTlsVerify;
    }

    public String getDefaultProject() {
        return defaultProject;
    }

    @DataBoundSetter
    public void setDefaultProject(String defaultProject) {
        this.defaultProject = Util.fixEmptyAndTrim(defaultProject);
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    @Override
    public String toString() {
        return String.format("OpenShift cluster [name:%s] [serverUrl:%s]", name, serverUrl);
    }


    @Extension
    public static class DescriptorImpl extends Descriptor<ClusterConfig> {

        @Override
        public String getDisplayName() {
            return "OpenShift Cluster";
        }

        public FormValidation doCheckName(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckServerUrl(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        // https://wiki.jenkins-ci.org/display/JENKINS/Credentials+Plugin
        // http://javadoc.jenkins-ci.org/credentials/com/cloudbees/plugins/credentials/common/AbstractIdCredentialsListBoxModel.html
        // https://github.com/jenkinsci/kubernetes-plugin/blob/master/src/main/java/org/csanchez/jenkins/plugins/kubernetes/KubernetesCloud.java
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            if (credentialsId == null) {
                credentialsId = "";
            }
            if (!Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER)) {
                // Important! Otherwise you expose credentials metadata to random web requests.
                return new StandardListBoxModel().includeCurrentValue(credentialsId);
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM, Jenkins.getInstance(), OpenShiftTokenCredentials.class)
                    //.includeAs(ACL.SYSTEM, Jenkins.getInstance(), StandardUsernamePasswordCredentials.class)
                    // .includeAs(ACL.SYSTEM, Jenkins.getInstance(), StandardCertificateCredentials.class)
                    // TODO: Make own type for token or use the existing token generator auth type used by sync plugin? or kubernetes?
                    .includeCurrentValue(credentialsId);

        }


    }
}
