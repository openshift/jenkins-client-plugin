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

public class Label extends AbstractDescribableImpl<Label> implements
        Serializable {

    private final String name;
    private final String value;

    @DataBoundConstructor
    public Label(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public String getName(Map<String, String> overrides) {
        return BaseStep.getOverride(getName(), overrides);
    }

    public String getValue() {
        return value;
    }

    public String getValue(Map<String, String> overrides) {
        return BaseStep.getOverride(getValue(), overrides);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Label> {

        @Override
        public String getDisplayName() {
            return "Label";
        }

        public FormValidation doCheckName(@QueryParameter String name) {
            return FormValidation.validateRequired(name);
        }

        public FormValidation doCheckValue(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

    }

}
