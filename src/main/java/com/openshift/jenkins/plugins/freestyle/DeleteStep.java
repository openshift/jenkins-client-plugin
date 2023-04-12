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
package com.openshift.jenkins.plugins.freestyle;

import com.openshift.jenkins.plugins.freestyle.model.ResourceSelector;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class DeleteStep extends BaseStep {

    private boolean ignoreNotFound;

    private ResourceSelector selector;

    @DataBoundConstructor
    public DeleteStep() {
    }

    @DataBoundSetter
    public void setSelector(ResourceSelector selector) {
        this.selector = selector;
    }

    public ResourceSelector getSelector() {
        return selector;
    }

    public boolean isIgnoreNotFound() {
        return ignoreNotFound;
    }

    @DataBoundSetter
    public void setIgnoreNotFound(boolean ignoreNotFound) {
        this.ignoreNotFound = ignoreNotFound;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher,
            BuildListener listener) throws IOException, InterruptedException {
        final Map<String, String> overrides = consolidateEnvVars(listener, build, null);
        List<String> base = selector.asSelectionArgs(overrides);
        if (isIgnoreNotFound()) {
            base.add("--ignore-not-found");
        }
        return standardRunOcCommand(build, listener, "delete", base, toList(),
                toList());
    }

    @Extension
    public static final class DescriptorImpl extends BaseStepDescriptor {

        @Override
        public String getDisplayName() {
            return "OpenShift - Delete Resource(s)";
        }

    }
}
