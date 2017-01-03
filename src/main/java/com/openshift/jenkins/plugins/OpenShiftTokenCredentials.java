package com.openshift.jenkins.plugins;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import hudson.Extension;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;

// TODO: merge with https://github.com/jenkinsci/kubernetes-plugin/blob/master/src/main/java/org/csanchez/jenkins/plugins/kubernetes/OpenShiftTokenCredentialImpl.java  ?
public class OpenShiftTokenCredentials extends BaseStandardCredentials {

    private final Secret secret;

    @DataBoundConstructor
    public OpenShiftTokenCredentials(CredentialsScope scope, String id, String description, Secret secret) {
        super(scope, id, description);
        this.secret = secret;
    }

    public String getToken() {
        return secret.getPlainText();
    }

    public Secret getSecret() {
        return secret;
    }

    @Extension
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {
        @Override
        public String getDisplayName() {
            return "OpenShift Token";
        }
    }

}
