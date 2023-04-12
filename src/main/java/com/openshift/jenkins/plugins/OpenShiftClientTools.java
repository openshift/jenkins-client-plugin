/*
 * Copyright © 2016 Red Hat, Inc. (https://www.redhat.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.List;

/**
 * An installation of the OpenShift Client Tools.
 */
public class OpenShiftClientTools extends ToolInstallation implements
        EnvironmentSpecific<OpenShiftClientTools>,
        NodeSpecific<OpenShiftClientTools> {

    @DataBoundConstructor
    public OpenShiftClientTools(String name, String home,
            List<? extends ToolProperty<?>> properties) {
        super(name, home, properties);
    }

    @Override
    public OpenShiftClientTools forEnvironment(EnvVars environment) {
        return new OpenShiftClientTools(getName(),
                environment.expand(getHome()), getProperties());
    }

    @Override
    public OpenShiftClientTools forNode(Node node, TaskListener log)
            throws IOException, InterruptedException {
        return new OpenShiftClientTools(getName(), translateFor(node, log),
                getProperties().toList());
    }

    @Override
    public void buildEnvVars(EnvVars env) {
        if (getHome() != null) {
            env.put("PATH+OC", getHome());
        }
    }

    @Extension
    @Symbol("oc")
    public static class DescriptorImpl extends
            ToolDescriptor<OpenShiftClientTools> {

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
