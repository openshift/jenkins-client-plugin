package com.openshift.jenkins.plugins;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentSpecific;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolProperty;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.List;

/**
 * An installation of the OpenShift Client Tools.
 */
public class OpenShiftClientTools extends ToolInstallation implements EnvironmentSpecific<OpenShiftClientTools>, NodeSpecific<OpenShiftClientTools> {

    @DataBoundConstructor
    public OpenShiftClientTools(String name, String home, List<? extends ToolProperty<?>> properties) {
        super(name, home, properties);
    }

    @Override
    public OpenShiftClientTools forEnvironment(EnvVars environment) {
        return new OpenShiftClientTools(getName(), environment.expand(getHome()), getProperties());
    }

    @Override
    public OpenShiftClientTools forNode(Node node, TaskListener log) throws IOException, InterruptedException {
        return new OpenShiftClientTools(getName(), translateFor(node, log), getProperties().toList());
    }

    @Extension
    public static class DescriptorImpl extends ToolDescriptor<OpenShiftClientTools> {

        @Override
        public String getDisplayName() {
            return "OpenShift Client Tools";
        }

        @Override
        public OpenShiftClientTools[] getInstallations() {
            load();
            return super.getInstallations();
        }

        @Override
        public void setInstallations(OpenShiftClientTools... installations) {
            super.setInstallations(installations);
            save();
        }

        @Override
        public List<? extends ToolInstaller> getDefaultInstallers() {
            return super.getDefaultInstallers();
        }

    }

}
