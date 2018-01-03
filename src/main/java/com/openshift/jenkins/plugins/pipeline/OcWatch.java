package com.openshift.jenkins.plugins.pipeline;

import com.openshift.jenkins.plugins.util.ClientCommandBuilder;
import com.openshift.jenkins.plugins.util.QuietTaskListenerFactory;
import hudson.*;
import hudson.model.TaskListener;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.durabletask.BourneShellScript;
import org.jenkinsci.plugins.durabletask.Controller;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.durabletask.WindowsBatchScript;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.inject.Inject;
import java.io.InputStream;
import java.util.List;
import java.util.logging.Logger;

import static com.openshift.jenkins.plugins.pipeline.OcAction.exitStatusRaceConditionBugWorkaround;

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

        public Void run() {
            getContext().saveState();
            listener.getLogger().println("Entering watch");

            Integer exitStatus = -1;
            try {

                FilePath stderrTmp = filePath.createTextTempFile("watchstderr", ".txt", "", false);
                try {

                    master:
                    while (true) {
                        String commandString = step.cmdBuilder.asString(false);

                        // pipe stdout to stderr to avoid any buffering
                        commandString += " 2> " + stderrTmp.getRemote() + " 1>&2";

                        final DurableTask task;
                        if (launcher.isUnix()) {
                            task = new BourneShellScript(commandString);
                        } else {
                            task = new WindowsBatchScript(commandString);
                        }

                        // Without this intervention, Durable task logs some
                        // extraneous details I don't want appearing in the
                        // console
                        // e.g. "[_OcWatch] Running shell script"
                        QuietTaskListenerFactory.QuietTasklistener quiet = QuietTaskListenerFactory.build(listener);
                        Controller dtc = task.launch(envVars, filePath, launcher, quiet);

                        try {
                            long reCheckSleep = 250;
                            boolean firstPass = true;
                            long outputSize = 0;
                            short tries = 0;

                            do {

                                byte[] newOutput;
                                try (InputStream is = stderrTmp.readFromOffset(outputSize)) {
                                    newOutput = IOUtils.toByteArray(is);
                                }
                                outputSize += newOutput.length;

                                if (newOutput.length > 0 || firstPass) {
                                    firstPass = false;
                                    reCheckSleep = Math.max(250, reCheckSleep / 2);

                                    if ( step.watchLoglevel > 0 ) {
                                        listener.getLogger().println("Received verbose watch output>>>");
                                        listener.getLogger().println(new String(newOutput, "utf-8"));
                                        listener.getLogger().println("<<<");
                                    }

                                    listener.getLogger().println("Running watch closure body");
                                    try {
                                        // Run body and get result
                                        Object o = getContext().newBodyInvoker().start().get();

                                        // If the watch body returns a Boolean and it is true, time to exit
                                        if (o instanceof Boolean && (Boolean)o) {
                                            listener.getLogger().println("\nwatch closure returned true; terminating watch");
                                            dtc.stop(filePath, launcher);
                                            break master;
                                        }

                                        continue;
                                    } catch ( InterruptedException tie ) { // timeout{} block interrupted us
                                        listener.getLogger().println("\nwatch closure interrupted (timeout?)");
                                        getContext().onFailure(tie);
                                        return null;
                                    } catch (Exception t) {
                                        String exceptionMsgs = t.getMessage();
                                        if (t.getCause() != null) {
                                            exceptionMsgs = exceptionMsgs + "; " + t.getCause().getMessage();
                                        }
                                        listener.getLogger().println(String.format("\nwatch closure threw an exception: \"%s\".\n", exceptionMsgs));
                                        getContext().onFailure(t);
                                        return null;
                                    }
                                }

                                // Gradually check less frequently if watch is not generating output
                                reCheckSleep = Math.min(10000, (int) (reCheckSleep * 1.2f));
                                listener.getLogger().println("Checking watch output again in " + reCheckSleep + "ms");
                                Thread.sleep(reCheckSleep);

                            } while ((exitStatus = exitStatusRaceConditionBugWorkaround(dtc, filePath, launcher)) == null);

                        } finally {
                            dtc.cleanup(filePath);
                        }

                        // Reaching this point means that the watch terminated -
                        // exitStatus will not be null
                        if (exitStatus.intValue() != 0) {
                            // Looks like the watch command encountered an error
                            throw new AbortException("watch invocation terminated with an error: "+ exitStatus);
                        }
                    }

                } finally {
                    stderrTmp.delete();
                }

            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                getContext().onFailure(e);
            }

            return null;
        }

    }

}
