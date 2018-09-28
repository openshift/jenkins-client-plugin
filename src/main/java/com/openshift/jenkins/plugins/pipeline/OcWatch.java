package com.openshift.jenkins.plugins.pipeline;

import com.openshift.jenkins.plugins.util.ClientCommandBuilder;
import com.openshift.jenkins.plugins.util.ClientCommandRunner;
import hudson.*;
import hudson.model.TaskListener;
import hudson.util.QuotedStringTokenizer;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;


public class OcWatch extends AbstractStepImpl {

    private static Logger LOGGER = Logger.getLogger(OcWatch.class.getName());

    public static final String FUNCTION_NAME = "_OcWatch";

    private final ClientCommandBuilder cmdBuilder;

    private final int watchLoglevel;

    @DataBoundConstructor
    public OcWatch(String server, String project, boolean skipTLSVerify, String caPath, String verb, List advArgs, List verbArgs,
                   List userArgs, List options, String token,
                   int logLevel) {
        this.watchLoglevel = logLevel;
        this.cmdBuilder = new ClientCommandBuilder(server, project, skipTLSVerify, caPath, verb,
                advArgs, verbArgs, userArgs, options, token, logLevel);
    }

    @Extension
    public static class DescriptorImpl extends AbstractStepDescriptorImpl {

        public DescriptorImpl() {
            super(Execution.class);
        }

        @Override
        public String getFunctionName() {
            return FUNCTION_NAME;
        }

        @Override
        public String getDisplayName() {
            return "Internal utility function for OpenShift DSL";
        }

        /**
         * This step is not meant to be used directly by DSL scripts. Setting
         * advanced causes this entry to show up at the bottom of the function
         * listing.
         */
        @Override
        public boolean isAdvanced() {
            return true;
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

    }

    // Based on implementation of WaitForConditionStep
    public static final class Execution extends AbstractSynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1;

        @Inject
        private transient OcWatch step;

        @StepContextParameter
        private transient FilePath filePath;

        @StepContextParameter
        private transient Launcher launcher;

        @StepContextParameter
        private transient EnvVars envVars;

        /**
         * Unused, just to force the descriptor to request it.
         */
        @StepContextParameter
        private transient TaskListener listener;

        public Void run() throws IOException, InterruptedException, ExecutionException {
            if (filePath != null)
                filePath.mkdirs();

            String[] ocCommand = QuotedStringTokenizer.tokenize(step.cmdBuilder.asString(false));

            // if watchSuccess[0] == true, oc is terminated by watch closure returning true
            final boolean[] watchSuccess = {false};
            final StringBuffer stderr = new StringBuffer();

            ClientCommandRunner runner = new ClientCommandRunner(ocCommand, filePath, envVars,
                    // stdout
                    new ClientCommandRunner.OutputObserver() {
                        @Override
                        public boolean onReadLine(String line) throws InterruptedException, IOException {
                            if (step.watchLoglevel > 0) {
                                listener.getLogger().println("Received verbose watch output>>>");
                                listener.getLogger().println(line);
                                listener.getLogger().println("<<<");
                            }
                            listener.getLogger().println("Running watch closure body");
                            try {
                                // Run body and get result
                                Object o = getContext().newBodyInvoker().start().get();
                                // If the watch body returns a Boolean and it is true, time to exit
                                if (o instanceof Boolean && (Boolean) o) {
                                    listener.getLogger().println("\nwatch closure returned true; terminating watch");
                                    watchSuccess[0] = true;
                                    return true; // stop the watch
                                }
                            } catch (ExecutionException t) {
                                String exceptionMsgs = t.getMessage();
                                if (t.getCause() != null) {
                                    exceptionMsgs = exceptionMsgs + "; " + t.getCause().getMessage();
                                }
                                listener.getLogger().println(String.format("\nwatch closure threw an exception: \"%s\".\n", exceptionMsgs));
                                throw new IOException(t);
                            }
                            return false;
                        }
                    },
                    // stderr
                    new ClientCommandRunner.OutputObserver() {
                        @Override
                        public boolean onReadLine(String line) {
                            stderr.append(line + '\n');
                            listener.getLogger().println(line);
                            return false;
                        }
                    }
            );
            int exitStatus = runner.run();
            if (!watchSuccess[0] && exitStatus != 0) {
                String msg = "OpenShift Client exited with status code " + Integer.toString(exitStatus)
                        + ", command: " + step.cmdBuilder.buildCommand(true)
                        + ", stderr: " + stderr.toString().trim();
                throw new AbortException(msg);
            }
            return null;
        }
    }
}
