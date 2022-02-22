package com.openshift.jenkins.plugins;

import java.io.Serializable;
import java.util.logging.Logger;

import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;

import hudson.Extension;
import hudson.PluginWrapper;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;

public class ClusterConfig extends AbstractDescribableImpl<ClusterConfig> implements Serializable {

    private static final String OPENSHIFT_SYNC_PLUGIN_NAME = "openshift-sync";
    private static final String IO_OPENSHIFT_CREDENTIALS_CLASS_NAME = "io.fabric8.jenkins.openshiftsync.OpenShiftTokenCredentials";
    private static final Logger LOGGER = Logger.getLogger(ClusterConfig.class.getName());

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
        return this.skipTlsVerify;
    }

    @DataBoundSetter
    public void setSkipTlsVerify(boolean skipTLSVerify) {
        this.skipTlsVerify = skipTLSVerify;
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

    /**
     * @return Returns a URL to contact the API server of the OpenShift cluster
     *         running this node or throws an Exception if it cannot be determined.
     */
    @Whitelisted
    public static String getHostClusterApiServerUrl() {
        String serviceHost = System.getenv("KUBERNETES_SERVICE_HOST");
        if (serviceHost == null) {
            throw new IllegalStateException(
                    "No clusterName information specified and unable to find `KUBERNETES_SERVICE_HOST` environment variable.");
        }
        String servicePort = System.getenv("KUBERNETES_SERVICE_PORT_HTTPS");
        if (servicePort == null) {
            throw new IllegalStateException(
                    "No clusterName information specified and unable to find `KUBERNETES_SERVICE_PORT_HTTPS` environment variable.");
        }
        return "https://" + serviceHost + ":" + servicePort;
    }

    /**
     * Takes in defaults, with assumption that the 'env' pipeline global var is
     * better populated as pipelines evolve
     * 
     * @param defaultHost the default host
     * @param defaultPort the default port
     * @return Returns a URL to contact the API server of the OpenShift cluster
     *         running this node or throws an Exception if it cannot be determined.
     */
    @Whitelisted
    public static String getHostClusterApiServerUrl(String defaultHost, String defaultPort) {
        try {
            return getHostClusterApiServerUrl();
        } catch (IllegalStateException e) {
            if (defaultHost != null && defaultHost.length() > 0 && defaultPort != null && defaultPort.length() > 0) {
                return "https://" + defaultHost + ":" + defaultPort;
            } else {
                throw e;
            }
        }
    }

    // https://wiki.jenkins-ci.org/display/JENKINS/Credentials+Plugin
    // http://javadoc.jenkins-ci.org/credentials/com/cloudbees/plugins/credentials/common/AbstractIdCredentialsListBoxModel.html
    // https://github.com/jenkinsci/kubernetes-plugin/blob/master/src/main/java/org/csanchez/jenkins/plugins/kubernetes/KubernetesCloud.java
    public static ListBoxModel doFillCredentialsIdItems(String credentialsId) {
        if (credentialsId == null) {
            credentialsId = "";
        }

        StandardListBoxModel standardListBoxModel = new StandardListBoxModel();
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            // Important! Otherwise you expose credentials metadata to random web requests.
            return standardListBoxModel.includeCurrentValue(credentialsId);
        }

        standardListBoxModel.includeEmptyValue();
        standardListBoxModel.includeAs(ACL.SYSTEM, Jenkins.get(), OpenShiftTokenCredentials.class);
        // openshift-sync-plugin:1.0.53+ does not depend anymore on openshift-client
        // plugin. Hence, it has its own credential type. To make it loadable by the
        // client plugin, we need get the class from the plugin itself using the
        // relevent class loader. If the plugin is not installed, it is just ignored
        try {
            PluginWrapper plugin = Jenkins.get().pluginManager.getPlugin(OPENSHIFT_SYNC_PLUGIN_NAME);
            Class clazz = plugin.classLoader.loadClass(IO_OPENSHIFT_CREDENTIALS_CLASS_NAME);
            standardListBoxModel.includeAs(ACL.SYSTEM, Jenkins.get(), clazz);
        } catch (ClassNotFoundException e) {
            LOGGER.warning("Class not found: " + IO_OPENSHIFT_CREDENTIALS_CLASS_NAME);
        }
        standardListBoxModel.includeCurrentValue(credentialsId);
        return standardListBoxModel;
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

        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            // It is valid to choose no default credential, so enable 'includeEmpty'
            return ClusterConfig.doFillCredentialsIdItems(credentialsId);
        }
    }
}
