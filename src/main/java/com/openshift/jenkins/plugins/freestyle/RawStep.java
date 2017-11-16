package com.openshift.jenkins.plugins.freestyle;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.FormValidation;

import java.io.IOException;
import java.util.Map;

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
    
    public String getCommand() {
        return command;
    }
    
    public String getCommand(Map<String, String> overrides) {
        return getOverride(getCommand(), overrides);
    }

    public String getArguments() {
        return arguments;
    }

    public String getArguments(Map<String, String> overrides) {
        return getOverride(getArguments(), overrides);
    }

    @Override
    public boolean perform(final AbstractBuild build, Launcher launcher,
            final BuildListener listener) throws IOException,
            InterruptedException {
        final Map<String, String> overrides = consolidateEnvVars(listener, build, launcher);
        return withTempInput("markup", getCommand(overrides), new WithTempInputRunnable() {
            @Override
            public boolean perform(String markupFilename) throws IOException,
                    InterruptedException {
                return standardRunOcCommand(build, listener, getCommand(overrides),
                        toList(getArguments(overrides)), toList(), toList(), toList());
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
