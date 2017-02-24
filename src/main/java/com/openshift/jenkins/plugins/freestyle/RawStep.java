package com.openshift.jenkins.plugins.freestyle;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.FormValidation;

import java.io.IOException;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;


public class RawStep extends BaseStep {

    private final String command;
    private final String arguments;
    
    @DataBoundConstructor
    public RawStep(String command, String arguments) {
        this.command = command;
        this.arguments = arguments;
    }

    @Override
    public boolean perform(final AbstractBuild build, Launcher launcher, final BuildListener listener) throws IOException, InterruptedException {
        return withTempInput("markup", command, new WithTempInputRunnable() {
            @Override
            public boolean perform(String markupFilename) throws IOException, InterruptedException {
                return standardRunOcCommand( build, listener, command,
                        toList(arguments),
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
            return "OpenShift - Generic OC Invocation";
        }

        public FormValidation doCommand(@QueryParameter String command) {
            return FormValidation.validateRequired(command);
        }

        public FormValidation doArguments(@QueryParameter String arguments) {
            return FormValidation.validateRequired(arguments);
        }

    }
}
