package com.openshift.jenkins.plugins.pipeline;

import com.openshift.jenkins.plugins.util.ClientCommandBuilder;
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
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
                verb, advArgs, verbArgs, userArgs, options, token, logLevel);
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

        public HashMap toMap() {
            HashMap m = new HashMap();
            m.put("verb", verb);
            m.put("cmd", cmd);
            m.put("out", out);
            m.put("err", err);
            m.put("reference", reference);
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

            ByteArrayOutputStream stdErr = new ByteArrayOutputStream();
            BufferedOutputStream errBuf = new BufferedOutputStream(stdErr);
            ByteArrayOutputStream stdOut = new ByteArrayOutputStream();
            BufferedOutputStream outBuf = new BufferedOutputStream(stdOut);

            String commandString = step.cmdBuilder.asString(false);
            String[] command = QuotedStringTokenizer.tokenize(commandString);
            command = ClientCommandBuilder.fixPathInCommandArray(command, envVars, listener, filePath, launcher, step.verbose);

            Proc proc = null;
            try {
                Launcher.ProcStarter ps = launcher.launch().cmds(Arrays.asList(command)).envs(envVars).pwd(filePath).quiet(true).stdout(outBuf).stderr(errBuf);
                proc = ps.start();
                long reCheckSleep = 250;
                int outputSize = 0;
                while (proc.isAlive()) {
                    Thread.sleep(reCheckSleep);

                    if (step.streamStdOutToConsolePrefix != null &&
                        step.streamStdOutToConsolePrefix.trim().length() > 0) {
                        byte[] newOutput = stdOut.toByteArray();
                        if (newOutput.length > 0) {
                            if (newOutput.length > outputSize) { 
                                printToConsole(new String(Arrays.copyOfRange(newOutput, outputSize, newOutput.length), StandardCharsets.UTF_8));
                            }
                            outputSize = newOutput.length;
                            // If we are streaming to console and getting output,
                            // keep sleep duration small.
                            reCheckSleep = 1000;
                            continue;
                        }
                    }
                    
                    if (reCheckSleep < 10000) { // Gradually check less
                        // frequently for slow execution
                        // tasks
                        reCheckSleep *= 1.2f;
                    }
                }
                int rc = proc.join();
                outBuf.flush();
                errBuf.flush();

                if (step.streamStdOutToConsolePrefix != null
                        && step.streamStdOutToConsolePrefix.trim().length() > 0) {
                    byte[] newOutput = stdOut.toByteArray();
                    if (newOutput.length > outputSize) {
                        printToConsole(new String(Arrays.copyOfRange(newOutput, outputSize, newOutput.length), StandardCharsets.UTF_8));
                    }
                    listener.getLogger().println(); // final newline if output does not contain it.
                }
        

                OcActionResult result = new OcActionResult();
                result.verb = step.cmdBuilder.verb;
                result.cmd = step.cmdBuilder.asString(true);
                result.reference = step.reference;
                result.status = rc;
                result.out = stdOut.toString("UTF-8").trim();
                result.err = stdErr.toString("UTF-8").trim();

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
            } finally {
                if (proc != null && proc.isAlive()) {
                    proc.kill();
                }
                stdOut.close();
                outBuf.close();
                stdErr.close();
                errBuf.close();
            }
        }

    }

}
