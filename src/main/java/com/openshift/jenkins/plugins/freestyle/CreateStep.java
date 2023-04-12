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

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.FormValidation;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.util.Map;

public class CreateStep extends BaseStep {

    private final String jsonyaml;

    @DataBoundConstructor
    public CreateStep(String jsonyaml) {
        this.jsonyaml = jsonyaml;
    }

    public String getJsonyaml() {
        return jsonyaml;
    }

    public String getJsonyaml(Map<String, String> overrides) {
        return getOverride(getJsonyaml(), overrides);
    }

    @Override
    public boolean perform(final AbstractBuild build, Launcher launcher,
            final BuildListener listener) throws IOException,
            InterruptedException {
        final Map<String, String> overrides = consolidateEnvVars(listener, build, launcher);
        return withTempInput("markup", getJsonyaml(overrides), new WithTempInputRunnable() {
            @Override
            public boolean perform(String markupFilename) throws IOException,
                    InterruptedException {
                return standardRunOcCommand(build, listener, "create",
                        toList("-f", markupFilename), toList(), toList());
            }
        });
    }

    @Extension
    public static final class DescriptorImpl extends BaseStepDescriptor {

        @Override
        public String getDisplayName() {
            return "OpenShift - Create Resource(s)";
        }

        public FormValidation doCheckJsonyaml(@QueryParameter String jsonyaml) {
            return FormValidation.validateRequired(jsonyaml);
        }

    }
}
