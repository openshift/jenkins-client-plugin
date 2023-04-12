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
package com.openshift.jenkins.plugins.pipeline;

import groovy.lang.Binding;
import hudson.Extension;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

import javax.annotation.Nonnull;

/**
 * Defines the "openshift" global variable in pipeline DSL scripts. It's
 * attributes are defined by the content of
 * resources/com/openshift/jenkins/plugins/OpenShiftGlobalVariable.groovy
 */
@Extension
public class OpenShiftGlobalVariable extends GlobalVariable {

    @Nonnull
    @Override
    public String getName() {
        return "openshift";
    }

    @Nonnull
    @Override
    public Object getValue(@Nonnull CpsScript script) throws Exception {
        Binding binding = script.getBinding();
        script.println();
        Object openshift;
        if (binding.hasVariable(getName())) {
            openshift = binding.getVariable(getName());
        } else {
            // Note that if this were a method rather than a constructor, we
            // would need to mark it @NonCPS lest it throw
            // CpsCallableInvocation.
            openshift = script.getClass().getClassLoader()
                    .loadClass("com.openshift.jenkins.plugins.OpenShiftDSL")
                    .getConstructor(CpsScript.class).newInstance(script);
            binding.setVariable(getName(), openshift);
        }
        return openshift;

    }
}
