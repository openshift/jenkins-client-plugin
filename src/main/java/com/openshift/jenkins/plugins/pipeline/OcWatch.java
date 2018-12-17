package com.openshift.jenkins.plugins.pipeline;

import com.openshift.jenkins.plugins.util.ClientCommandBuilder;
import hudson.*;
import hudson.model.TaskListener;
import hudson.remoting.RemoteOutputStream;
import hudson.util.QuotedStringTokenizer;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.inject.Inject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
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
            if (filePath != null && !filePath.exists()) {
                filePath.mkdirs();
            }
            getContext().saveState();
            listener.getLogger().println("Entering watch");

            int exitStatus = -1;
            try {

                // pipe stdout to stderr to avoid any buffering
                FilePath stderrTmp = filePath.createTextTempFile("watchstderr", ".txt", "", false);
                FileOutputStream fos = new FileOutputStream(stderrTmp.getRemote());
                RemoteOutputStream ros = new RemoteOutputStream(fos);
                Path path = Paths.get(stderrTmp.getRemote());

                String commandString = step.cmdBuilder.asString(false);
                String[] command = QuotedStringTokenizer.tokenize(commandString);
                command = ClientCommandBuilder.fixPathInCommandArray(command, envVars);
                Proc proc = null;

                try {

                    int outputSize = 0;
                    master:
                    while (true) {
                        Launcher.ProcStarter ps = launcher.launch().cmds(Arrays.asList(command)).envs(envVars).pwd(filePath).quiet(true).stdout(ros).stderr(ros);
                        proc = ps.start();

                        long reCheckSleep = 250;
                        boolean firstPass = true;

                        do {
                            // FYI ... the old form of stdoutTmp.readFromOffset no longer worked for agent/container pods
                            // once we moved to Launcher.ProcStater
                            byte[] newOutput = Files.readAllBytes(path);

                            if ((newOutput.length > 0 && newOutput.length > outputSize) || firstPass) {
                                firstPass = false;
                                reCheckSleep = Math.max(250, reCheckSleep / 2);

                                if ( step.watchLoglevel > 0 ) {
                                    listener.getLogger().println("Received verbose watch output>>>");
                                    listener.getLogger().println(new String(Arrays.copyOfRange(newOutput, outputSize, newOutput.length), "utf-8"));
                                    listener.getLogger().println("<<<");
                                }

                                listener.getLogger().println("Running watch closure body");
                                outputSize += newOutput.length;
                                try {
                                    // Run body and get result
                                    Object o = getContext().newBodyInvoker().start().get();

                                    // If the watch body returns a Boolean and it is true, time to exit
                                    if (o instanceof Boolean && (Boolean)o) {
                                        listener.getLogger().println("\nwatch closure returned true; terminating watch");
                                        proc.kill();
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

                        } while (proc.isAlive());
                        exitStatus = ps.join();

                        // Reaching this point means that the watch terminated -
                        // exitStatus will not be null
                        if (exitStatus != 0) {
                            // Looks like the watch command encountered an error
                            throw new AbortException("watch invocation terminated with an error: "+ exitStatus);
                        }
                    }

                } finally {
                    if (proc != null && proc.isAlive()) {
                        proc.kill();
                    }
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
