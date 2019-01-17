package com.openshift.jenkins.plugins.pipeline;

import com.openshift.jenkins.plugins.util.ClientCommandBuilder;
import hudson.*;
import hudson.model.TaskListener;
import hudson.util.QuotedStringTokenizer;

import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.inject.Inject;
import java.io.*;
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

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                BufferedOutputStream bos = new BufferedOutputStream(baos);

                String commandString = step.cmdBuilder.asString(false);
                String[] command = QuotedStringTokenizer.tokenize(commandString);
                Proc proc = null;

                try {

                    master:
                    while (true) {
                        Launcher.ProcStarter ps = launcher.launch().cmds(Arrays.asList(command)).envs(envVars).pwd(filePath).quiet(true).stdout(bos).stderr(bos);
                        proc = ps.start();

                        long reCheckSleep = 250;
                        int outputSize = 0;
                        int jsonRetries = 0;
                        do {
                            byte[] newOutput = baos.toByteArray();

                            if ( step.watchLoglevel > 0 ) {
                                if (newOutput.length > outputSize) {
                                    listener.getLogger().println("Received verbose watch output>>>");
                                    listener.getLogger().println(new String(Arrays.copyOfRange(newOutput, outputSize, newOutput.length), "utf-8"));
                                    listener.getLogger().println("<<<");
                                }
                                // If we are streaming to console and getting output,
                                // keep sleep duration small.
                                reCheckSleep = 1000;
                            }
                            outputSize = newOutput.length;

                            listener.getLogger().println("Running watch closure body");
                            try {
                                bos.flush();
                                // Run body and get result
                                Object o = getContext().newBodyInvoker().start().get();
                                
                                // If the watch body returns a Boolean and it is true, time to exit
                                if (o instanceof Boolean && (Boolean)o) {
                                    listener.getLogger().println("\nwatch closure returned true; terminating watch");
                                    proc.kill();
                                    break master;
                                }
                                listener.getLogger().println("watch closure returned " + o);
                                jsonRetries = 0;

                            } catch ( InterruptedException tie ) { // timeout{} block interrupted us
                                listener.getLogger().println("\nwatch closure interrupted (timeout?)");
                                getContext().onFailure(tie);
                                return null;
                            } catch (Throwable t) {
                                if (t instanceof groovy.json.JsonException) {
                                    // we've seen instances where if the watch closer susequently calls oc and it processes output 
                                    // before oc has finished updating it we can get json formatting 
                                    // exceptions processing the output ... we can retry here
                                    if (jsonRetries < 5) {
                                        listener.getLogger().println("watch closer got json formatting exception, trying again");
                                        jsonRetries++;
                                    } else {
                                        String exceptionMsgs = t.getMessage();
                                        if (t.getCause() != null) {
                                            exceptionMsgs = exceptionMsgs + "; " + t.getCause().getMessage();
                                        }
                                        listener.getLogger().println(String.format("\nwatch closure threw an exception: \"%s\".\n", exceptionMsgs));
                                        getContext().onFailure(t);
                                        return null;
                                    }
                                } else {
                                    String exceptionMsgs = t.getMessage();
                                    if (t.getCause() != null) {
                                        exceptionMsgs = exceptionMsgs + "; " + t.getCause().getMessage();
                                    }
                                    listener.getLogger().println(String.format("\nwatch closure threw an exception: \"%s\".\n", exceptionMsgs));
                                    getContext().onFailure(t);
                                    return null;
                                }
                            }

                            if (reCheckSleep < 10000) { // Gradually check less
                                // frequently for slow watch closures
                                // wrt returning true
                                reCheckSleep *= 1.2f;
                            }
                            listener.getLogger().println("Checking watch output and running watch closure again in " + reCheckSleep + "ms having processed " + outputSize + " bytes of watch output so far");
                            Thread.sleep(reCheckSleep);

                        } while (proc.isAlive());
                        exitStatus = ps.join();
                        bos.flush();

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
                    bos.close();
                    baos.close();
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
