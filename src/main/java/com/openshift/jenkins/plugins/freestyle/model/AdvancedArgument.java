package com.openshift.jenkins.plugins.freestyle.model;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.openshift.jenkins.plugins.freestyle.BaseStep;

import java.io.Serializable;
import java.util.Map;

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
    
    public String getValue(Map<String, String> overrides) {
        return BaseStep.getOverride(getValue(), overrides);
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
