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
