package com.openshift.jenkins.plugins.pipeline;

import com.openshift.jenkins.plugins.util.ClientCommandBuilder;
import com.openshift.jenkins.plugins.util.QuietTaskListenerFactory;
import hudson.*;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.durabletask.BourneShellScript;
import org.jenkinsci.plugins.durabletask.Controller;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.durabletask.WindowsBatchScript;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

public class OcAction extends AbstractStepImpl {

    public static final String FUNCTION_NAME = "_OcAction";

    private final ClientCommandBuilder cmdBuilder;
    private final boolean verbose;
    protected final String streamStdOutToConsolePrefix;
    private final HashMap<String, String> reference;

    @DataBoundConstructor
    public OcAction(String server, String project, String verb, List verbArgs,
            List userArgs, List options, List verboseOptions, String token,
            String streamStdOutToConsolePrefix,
            HashMap<String, String> reference, int logLevel) {
        this.cmdBuilder = new ClientCommandBuilder(server, project, verb,
                verbArgs, userArgs, options, verboseOptions, token, logLevel);
        this.verbose = (logLevel > 0);
        this.streamStdOutToConsolePrefix = streamStdOutToConsolePrefix;
        // Reference is used to output information about, for example, file
        // contents not visibile in the command line.
        this.reference = reference == null ? (new HashMap<String, String>())
                : reference;
    }

    public static Integer exitStatusRaceConditionBugWorkaround(Controller dtc,
            FilePath filePath, Launcher launcher) throws InterruptedException,
            IOException {
        for (int tries = 30; tries > 0; tries--) {
            try {
                // exitStatus can throw an IOException (reporting a
                // NumberFormatException) if the PID file has been created but
                // not
                // populated, or if the PID file has not yet been created. Make
                // sure it stops throwing this exception before continuing.
                return dtc.exitStatus(filePath, launcher);
            } catch (IOException ioe) {
                if (tries == 1) {
                    throw ioe;
                }
                Thread.sleep(125);
            }
        }
        launcher.getListener()
                .getLogger()
                .println(
                        "After 30 retries, unable to get exit status for "
                                + filePath.toURI().toString());
        return -1;
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

        private boolean firstPrint = true;

        private void printToConsole(String s) {
            final String prefix = "[" + step.streamStdOutToConsolePrefix + "] ";
            if (firstPrint) {
                listener.getLogger().print(prefix);
                firstPrint = false;
            }
            listener.getLogger().print(s.replace("\n", "\n" + prefix));
            listener.getLogger().flush();
        }

        @Override
        protected OcActionResult run() throws Exception {

            String commandString = step.cmdBuilder.asString(false);
            String redactedCommandString = step.cmdBuilder.asString(true);

            FilePath stdoutTmp = filePath.createTextTempFile("ocstdout",
                    ".txt", "", false);
            FilePath stderrTmp = filePath.createTextTempFile("ocstderr",
                    ".txt", "", false);
            commandString += " >> " + stdoutTmp.getRemote() + " 2>> "
                    + stderrTmp.getRemote();

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
            
            // explicitly set KUBECONFIG such that use of this plugin in a non-openshift pod will not pick up a rogue .kube/config
            // when we fork `oc`; for now, using the default setting when running in a openshift pod (presuming that is safe enough)
            envVars.put("KUBECONFIG", "/var/lib/origin/openshift.local.config/master/admin.kubeconfig");

            try {
                final DurableTask task;
                if (launcher.isUnix()) {
                    task = new BourneShellScript(commandString);
                } else {
                    task = new WindowsBatchScript(commandString);
                }

                // Without this intervention, Durable task logs some extraneous
                // details I don't want appearing in the console
                // e.g. "[_OcAction] Running shell script"
                QuietTaskListenerFactory.QuietTasklistener quiet = QuietTaskListenerFactory
                        .build(listener);
                Controller dtc = task
                        .launch(envVars, filePath, launcher, quiet);

                ByteArrayOutputStream stdErr = new ByteArrayOutputStream();
                ByteArrayOutputStream stdOut = new ByteArrayOutputStream();

                long reCheckSleep = 250;
                Integer exitStatus;
                while ((exitStatus = exitStatusRaceConditionBugWorkaround(dtc,
                        filePath, launcher)) == null) {
                    Thread.sleep(reCheckSleep);
                    byte[] newOutput;
                    try (InputStream is = stdoutTmp.readFromOffset(stdOut
                            .size())) {
                        newOutput = IOUtils.toByteArray(is);
                    }
                    stdOut.write(newOutput);
                    if (newOutput.length > 0
                            && step.streamStdOutToConsolePrefix != null) {
                        printToConsole(new String(newOutput,
                                StandardCharsets.UTF_8));
                        // If we are streaming to console and getting output,
                        // keep sleep duration small.
                        reCheckSleep = 1000;
                        continue;
                    }
                    if (reCheckSleep < 10000) { // Gradually check less
                                                // frequently for slow execution
                                                // tasks
                        reCheckSleep *= 1.2f;
                    }
                }

                byte[] newOutput;
                try (InputStream is = stdoutTmp.readFromOffset(stdOut.size())) {
                    newOutput = IOUtils.toByteArray(is);
                }
                stdOut.write(newOutput);
                if (step.streamStdOutToConsolePrefix != null) {
                    printToConsole(new String(newOutput, StandardCharsets.UTF_8));
                    listener.getLogger().println(); // final newline if output
                                                    // does not contain it.
                }

                try (InputStream is = stderrTmp.read()) {
                    stdErr.write(IOUtils.toByteArray(is));
                }

                OcActionResult result = new OcActionResult();
                result.verb = step.cmdBuilder.verb;
                result.cmd = redactedCommandString;
                result.status = exitStatus.intValue();
                result.out = stdOut.toString("UTF-8").trim();
                result.err = stdErr.toString("UTF-8").trim();
                result.reference = step.reference;

                if (step.verbose) {
                    listener.getLogger().println("Verbose sub-step output:");
                    listener.getLogger().println("\tCommand> " + result.cmd);
                    listener.getLogger().println("\tStatus> " + result.status);
                    listener.getLogger().println("\tStdOut>" + result.out);
                    listener.getLogger().println("\tStdErr> " + result.err);
                    listener.getLogger().println(
                            "\tReference> " + result.reference);
                }

                dtc.cleanup(filePath);
                return result;
            } finally {
                stdoutTmp.delete();
                stderrTmp.delete();
            }
        }

    }

}
