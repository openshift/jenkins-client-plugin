package com.openshift.jenkins.plugins.freestyle;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;

public class CreateStep extends BaseStep {

    private final String jsonyaml;

    @DataBoundConstructor
    public CreateStep(String jsonyaml) {
        this.jsonyaml = jsonyaml;
    }

    public String getJsonyaml() {
        return jsonyaml;
    }

    @Override
    public boolean perform(final AbstractBuild build, Launcher launcher, final BuildListener listener) throws IOException, InterruptedException {
        return withTempInput("markup", jsonyaml, new WithTempInputRunnable() {
            @Override
            public boolean perform(String markupFilename) throws IOException, InterruptedException {
                return standardRunOcCommand( build, listener, "create",
                        toList( "-f", markupFilename ),
                        toList(),
                        toList(),
                        toList()
                );
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
