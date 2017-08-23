package com.openshift.jenkins.plugins.freestyle;

import com.openshift.jenkins.plugins.freestyle.model.ResourceSelector;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.IOException;
import java.util.List;

public class DeleteStep extends BaseStep {

    private boolean ignoreNotFound;

    private ResourceSelector selector;

    @DataBoundConstructor
    public DeleteStep() {
    }

    @DataBoundSetter
    public void setSelector(ResourceSelector selector) {
        this.selector = selector;
    }

    public ResourceSelector getSelector() {
        return selector;
    }

    public boolean isIgnoreNotFound() {
        return ignoreNotFound;
    }

    @DataBoundSetter
    public void setIgnoreNotFound(boolean ignoreNotFound) {
        this.ignoreNotFound = ignoreNotFound;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher,
            BuildListener listener) throws IOException, InterruptedException {
        List<String> base = selector.asSelectionArgs();
        if (isIgnoreNotFound()) {
            base.add("--ignore-not-found");
        }
        return standardRunOcCommand(build, listener, "delete", base, toList(),
                toList(), toList());
    }

    @Extension
    public static final class DescriptorImpl extends BaseStepDescriptor {

        @Override
        public String getDisplayName() {
            return "OpenShift - Delete Resource(s)";
        }

    }
}
