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

import com.google.common.base.Strings;
import com.openshift.jenkins.plugins.freestyle.BaseStep;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ResourceSelector extends AbstractDescribableImpl<ResourceSelector>
        implements Serializable {

    private static final String SELECT_BY_NAMES = "SELECT_BY_NAMES";
    private static final String SELECT_BY_KIND = "SELECT_BY_KIND";

    private String kind;
    private List<Label> labels;

    private List<ResourceName> names;

    /*public boolean isSelectionType(String type) {
        if (SELECT_BY_KIND.equals(type)) {
            return !Strings.isNullOrEmpty(kind);
        }
        return true;
    }*/

    @DataBoundConstructor
    public ResourceSelector() {
    }

    @DataBoundSetter
    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getKind() {
        return kind;
    }

    public String getKind(Map<String, String> overrides) {
        return BaseStep.getOverride(getKind(), overrides);
    }

    @DataBoundSetter
    public void setLabels(List<Label> labels) {
        this.labels = labels;
    }

    public List<Label> getLabels() {
        return labels;
    }

    @DataBoundSetter
    public void setNames(List<ResourceName> names) {
        this.names = names;
    }

    public List<ResourceName> getNames() {
        return names;
    }

    public List<String> asSelectionArgs(Map<String, String> overrides) {
        ArrayList<String> args = new ArrayList<String>();

        if (names != null) {
            for (ResourceName res : names) {
                args.add(res.getName(overrides));
            }
        } else {
            args.add(getKind(overrides));

            if (labels != null) {
                StringBuilder labelBuilder = new StringBuilder();
                for (Label e : labels) {
                    labelBuilder.append(e.getName(overrides) + "=" + e.getValue(overrides) + ",");
                }
                labelBuilder.deleteCharAt(labelBuilder.length() - 1);
                args.add("-l " + labelBuilder.toString());
            }

        }

        return args;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ResourceSelector> {

        @Override
        public String getDisplayName() {
            return "Selection";
        }

        @Override
        public ResourceSelector newInstance(StaplerRequest req,
                JSONObject formData) throws FormException {
            ResourceSelector s = super.newInstance(req, formData);

            String selectionType = formData.getString("selectionType");
            System.out.println("parms2: " + selectionType);

            if (SELECT_BY_KIND.equals(selectionType)) {
                s.names = null;
            }
            if (SELECT_BY_NAMES.equals(selectionType)) {
                s.kind = null;
                s.labels = null;
            }
            return s;
        }

    }

}
