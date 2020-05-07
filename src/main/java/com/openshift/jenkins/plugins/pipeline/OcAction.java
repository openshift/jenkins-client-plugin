package com.openshift.jenkins.plugins.pipeline;

import com.openshift.jenkins.plugins.util.ClientCommandBuilder;
import com.openshift.jenkins.plugins.util.ClientCommandOutputCleaner;
import com.openshift.jenkins.plugins.util.ClientCommandRunner;
import hudson.*;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.QuotedStringTokenizer;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.inject.Inject;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

public class OcAction extends AbstractStepImpl {

    public static final Logger LOGGER = Logger.getLogger(OcAction.class.getName());

    public static final String FUNCTION_NAME = "_OcAction";

    private final ClientCommandBuilder cmdBuilder;
    private final boolean verbose;
    protected final String streamStdOutToConsolePrefix;
    private final HashMap<String, String> reference;

    @DataBoundConstructor
    public OcAction(String server, String project, boolean skipTLSVerify, String caPath,
                    String verb, List advArgs, List verbArgs, List userArgs, List options, String token,
                    String streamStdOutToConsolePrefix,
                    HashMap<String, String> reference, int logLevel) {
        this.cmdBuilder = new ClientCommandBuilder(server, project, skipTLSVerify, caPath,
                verb, advArgs, verbArgs, userArgs, options, token, logLevel, (streamStdOutToConsolePrefix != null && !streamStdOutToConsolePrefix.trim().isEmpty()));
        this.verbose = (logLevel > 0);
        this.streamStdOutToConsolePrefix = streamStdOutToConsolePrefix;
        // Reference is used to output information about, for example, file
        // contents not visibile in the command line.
        this.reference = reference == null ? (new HashMap<String, String>())
                : reference;
    }

    public static class OcActionResult implements Serializable {

        @Whitelisted
        public String verb;
        @Whitelisted
        public String cmd;
        @Whitelisted
        public String out;
        @Whitelisted
        public String err;
        @Whitelisted
        public int status;
        @Whitelisted
        public HashMap<String, String> reference = new HashMap<String, String>();
        @Whitelisted
        public boolean verbose = false;

        public HashMap toMap() {
            HashMap m = new HashMap();
            m.put("verb", verb);
            m.put("cmd", cmd);
            m.put("out", out);
            m.put("err", ClientCommandOutputCleaner.redactSensitiveData(err));
            if (verbose) {
                m.put("reference", reference);
            }
            m.put("status", status);
            return m;
        }

        @Whitelisted
        public String toString() {
            return toMap().toString();
        }

        public boolean isFailed() {
            return status != 0;
        }

        public void failIf(String failMessage) throws AbortException {
            if (isFailed()) {
                throw new AbortException(failMessage + "; action failed: "
                        + toString());
            }
        }
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

    }

    public static class Execution extends
            AbstractSynchronousNonBlockingStepExecution<OcActionResult> {

        private static final long serialVersionUID = 1L;

        @Inject
        private transient OcAction step;

        @StepContextParameter
        private transient TaskListener listener;
        @StepContextParameter
        private transient Launcher launcher;
        @StepContextParameter
        private transient EnvVars envVars;
        @StepContextParameter
        private transient Run<?, ?> runObj;
        @StepContextParameter
        private transient FilePath filePath;
        @StepContextParameter
        private transient Executor executor;
        @StepContextParameter
        private transient Computer computer;

        private void printToConsole(String line) {
            if (step.streamStdOutToConsolePrefix == null
                    || step.streamStdOutToConsolePrefix.trim().isEmpty()) {
                return;
            }
            final String prefix = "[" + step.streamStdOutToConsolePrefix + "] ";
            listener.getLogger().println(prefix + line);
            listener.getLogger().flush();
        }

        @Override
        protected OcActionResult run() throws IOException, InterruptedException, ExecutionException {
            if (filePath != null && !filePath.exists()) {
                filePath.mkdirs();
            }

            if (step.streamStdOutToConsolePrefix != null
                    && step.streamStdOutToConsolePrefix
                    .startsWith("start-build")) {
                listener.getLogger()
                        .println(
                                "NOTE: the selector returned when -F/--follow is supplied to startBuild() will be inoperative for the various selector operations.");
                listener.getLogger()
                        .println(
                                "Consider removing those options from startBuild and using the logs() command to follow the build output.");
            }
            String commandString = step.cmdBuilder.asString(false);
            String[] command = QuotedStringTokenizer.tokenize(commandString);
            command = ClientCommandBuilder.fixPathInCommandArray(command, envVars, listener, filePath, launcher, step.verbose);
            final StringBuffer stdout = new StringBuffer();
            final StringBuffer stderr = new StringBuffer();
            ClientCommandRunner runner = new ClientCommandRunner(command, filePath, envVars,
                    line -> { // got a line from stdout
                        // some of the k8s klog's like the cached_discovery.go V(3) logging ends up in StdOut
                        // vs. StdErr; so we employ a simple filter to discern and send these to stderr instead
                        if (line != null && line.contains(".go:")) {
                            stderr.append(line).append('\n');
                        } else {
                            stdout.append(line).append('\n');
                        }
                        printToConsole(line);
                        return false; // don't interrupt `oc`
                    },
                    line -> { // got a line from stderr
                        stderr.append(line).append('\n');
                        printToConsole(line);
                        return false; // don't interrupt `oc`
                    });

            int exitStatus = -1;
            try {
                exitStatus = runner.run(launcher);
            } catch (Throwable ex) {
                getContext().onFailure(ex);
            }
            OcActionResult result = new OcActionResult();
            result.status = exitStatus;
            result.verb = step.cmdBuilder.verb;
            result.cmd = step.cmdBuilder.asString(true);
            result.reference = step.reference;
            result.out = stdout.toString();
            result.err = stderr.toString();
            result.verbose = step.verbose;

            if (step.verbose) {
                listener.getLogger().println("Verbose sub-step output:");
                listener.getLogger().println("\tCommand> " + result.cmd);
                listener.getLogger().println("\tStatus> " + result.status);
                listener.getLogger().println("\tStdOut>" + result.out);
                listener.getLogger().println("\tStdErr> " + result.err);
                listener.getLogger().println(
                        "\tReference> " + result.reference);
            }
            return result;
        }
    }

}
