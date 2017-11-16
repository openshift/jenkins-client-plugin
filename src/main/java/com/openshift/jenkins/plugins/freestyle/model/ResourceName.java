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

public class ResourceName extends AbstractDescribableImpl<ResourceName>
        implements Serializable {

    private final String name;

    @DataBoundConstructor
    public ResourceName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getName(Map<String, String> overrides) {
        return BaseStep.getOverride(getName(), overrides);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ResourceName> {

        @Override
        public String getDisplayName() {
            return "Name";
        }

        public FormValidation doCheckValue(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

    }

}
