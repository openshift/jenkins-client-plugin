package com.openshift.jenkins.plugins.freestyle;

import com.google.common.base.Strings;
import com.openshift.jenkins.plugins.freestyle.model.ResourceSelector;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.FormValidation;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class WatchStep extends BaseStep {

    private String template;
    private String successPattern;
    private String failPattern;
    private ResourceSelector selector;

    @DataBoundConstructor
    public WatchStep() {
    }

    @DataBoundSetter
    public void setSelector(ResourceSelector selector) {
        this.selector = selector;
    }

    public ResourceSelector getSelector() {
        return selector;
    }

    @DataBoundSetter
    public void setTemplate(String template) {
        this.template = template;
    }

    public String getTemplate() {
        return template;
    }

    public String getTemplate(Map<String, String> overrides) {
        return getOverride(getTemplate(), overrides);
    }

    public String getSuccessPattern() {
        return successPattern;
    }

    public String getSuccessPattern(Map<String, String> overrides) {
        return getOverride(getSuccessPattern(), overrides);
    }

    @DataBoundSetter
    public void setSuccessPattern(String successPattern) {
        this.successPattern = successPattern;
    }

    public String getFailPattern() {
        return failPattern;
    }

    public String getFailPattern(Map<String, String> overrides) {
        return getOverride(getFailPattern(), overrides);
    }

    @DataBoundSetter
    public void setFailPattern(String failPattern) {
        this.failPattern = failPattern;
    }

    @Override
    public boolean perform(final AbstractBuild build, Launcher launcher,
            final BuildListener listener) throws IOException,
            InterruptedException {
        final Map<String, String> overrides = consolidateEnvVars(listener, build, launcher);
        final AtomicBoolean watchSatisfied = new AtomicBoolean(false);
        final AtomicBoolean watchResult = new AtomicBoolean(false);
        List<String> base = selector.asSelectionArgs(overrides);
        base.add("--watch");
        base.add("--template=" + getTemplate(overrides));
        base.add("-o=template");
        while (!watchSatisfied.get()) { // Watch can simply timeout, so we may
                                        // need to reinvoke. Loop until we get
                                        // true-positive feedback.
            final StringBuffer totalOutput = new StringBuffer();
            runOcCommand(build, listener, "get", base, toList(), toList(),
                    toList(), new OcProcessRunner() {

                        @Override
                        public boolean perform(ProcessBuilder pb)
                                throws IOException, InterruptedException {
                            pb.redirectErrorStream(true); // Merge stdout &
                                                          // stderr
                            final Process process = pb.start();
                            final InputStream output = process.getInputStream(); // stream
                                                                                 // for
                                                                                 // combined
                                                                                 // stdout
                                                                                 // &
                                                                                 // stderr

                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    byte buffer[] = new byte[1024];
                                    int count;
                                    try {
                                        while ((count = output.read(buffer)) != -1) {
                                            totalOutput.append(new String(
                                                    buffer, 0, count,
                                                    StandardCharsets.UTF_8));

                                            if (isVerbose()) { // If logging
                                                               // level is
                                                               // turned up,
                                                               // stream all
                                                               // output from
                                                               // the watch out
                                                               // to the console
                                                listener.getLogger().write(
                                                        buffer, 0, count);
                                            }

                                            // Don't allow the output to grow
                                            // without bound.
                                            // Just make sure we allow it to
                                            // grow larger than the user's
                                            // success/fail patterns.
                                            // We could measure them if we
                                            // wanted, but no one is going to be
                                            // looking for a pattern > 100K.
                                            if (totalOutput.length() > 200000) {
                                                totalOutput.delete(0, 100000);
                                            }

                                            if (totalOutput
                                                    .indexOf(getSuccessPattern(overrides)) > -1) {
                                                watchSatisfied.set(true);
                                                watchResult.set(true);
                                                listener.getLogger()
                                                        .println(
                                                                "Found success pattern: '"
                                                                        + getSuccessPattern(overrides)
                                                                        + "' in: \n>>>\n"
                                                                        + totalOutput
                                                                        + "\n<<<");
                                                process.destroy();
                                            }

                                            if (!Strings
                                                    .isNullOrEmpty(getFailPattern(overrides))
                                                    && totalOutput
                                                            .indexOf(getFailPattern(overrides)) > -1) {
                                                watchSatisfied.set(true);
                                                watchResult.set(false);
                                                listener.getLogger()
                                                        .println(
                                                                "Found failure pattern: '"
                                                                        + getFailPattern(overrides)
                                                                        + "' in: \n>>>\n"
                                                                        + totalOutput
                                                                        + "\n<<<");
                                                process.destroy();
                                            }

                                        }
                                    } catch (Exception e) {
                                        if (!watchSatisfied.get()) {
                                            // If the watch isn't yet satisfied
                                            // and we have an exception, then it
                                            // is a problem.
                                            listener.error("Error streaming process output");
                                            e.printStackTrace(listener
                                                    .getLogger());
                                        }
                                    }
                                }
                            }).start();

                            int status = process.waitFor();
                            if (!watchSatisfied.get() && status != 0) {
                                listener.getLogger().println(
                                        "Client tool watch terminated with error: "
                                                + status);
                                listener.getLogger().println(totalOutput);
                                watchSatisfied.set(true);
                                watchResult.set(false);

                            }
                            return true; // This value ignored. Only watchResult
                                         // matters.
                        }
                    });
        }

        return watchResult.get();
    }

    @Extension
    public static final class DescriptorImpl extends BaseStepDescriptor {

        @Override
        public String getDisplayName() {
            return "OpenShift - Watch Resource(s)";
        }

        public FormValidation doCheckTemplate(@QueryParameter String template) {
            return FormValidation.validateRequired(template);
        }

        public FormValidation doCheckSuccessPattern(
                @QueryParameter String successPattern) {
            return FormValidation.validateRequired(successPattern);
        }

        // failPattern is optional, so don't check.

    }
}
