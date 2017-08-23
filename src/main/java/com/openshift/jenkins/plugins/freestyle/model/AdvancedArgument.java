package com.openshift.jenkins.plugins.freestyle.model;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.Serializable;

public class AdvancedArgument extends AbstractDescribableImpl<AdvancedArgument>
        implements Serializable {

    private final String value;

    @DataBoundConstructor
    public AdvancedArgument(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<AdvancedArgument> {

        @Override
        public String getDisplayName() {
            return "Argument";
        }

        public FormValidation doCheckValue(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

    }

}
