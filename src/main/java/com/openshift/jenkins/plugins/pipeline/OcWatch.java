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
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.InputStream;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.openshift.jenkins.plugins.pipeline.OcAction.exitStatusRaceConditionBugWorkaround;

// Based on implementation of WaitForConditionStep
public class OcWatch extends Step {

    private static Logger LOGGER = Logger.getLogger(OcWatch.class.getName());

    public static final String FUNCTION_NAME = "_OcWatch";

    private final ClientCommandBuilder cmdBuilder;

    @DataBoundConstructor
    public OcWatch(String server, String project, String verb, List verbArgs, List userArgs, List options, List verboseOptions, String token, int logLevel ) {
        this.cmdBuilder = new ClientCommandBuilder( server,project,verb,verbArgs,userArgs,options,verboseOptions,token,logLevel);
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(cmdBuilder, context);
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

        private ClientCommandBuilder cmdBuilder;

        private transient FilePath filePath;

        private transient Launcher launcher;

        private transient EnvVars envVars;

        private transient TaskListener listener;

        Execution(ClientCommandBuilder cmdBuilder, StepContext context) {
            super(context);
            this.cmdBuilder = cmdBuilder;

            try {
                filePath = getContext().get(FilePath.class);
                launcher = getContext().get(Launcher.class);
                listener = getContext().get(TaskListener.class);
                envVars = getContext().get(EnvVars.class);
            } catch ( Exception e ) {
                throw new RuntimeException("Error initializing step", e);
            }

        }

        public Void run() {
            getContext().saveState();

            listener.getLogger().println( "Entering watch" );

            Integer exitStatus = -1;
            try {

                FilePath stderrTmp = filePath.createTextTempFile( "watchstderr", ".txt", "", false );
                try {

                    master:
                    while( true ) {
                        String commandString = cmdBuilder.asString(false);
                        commandString += " 2> " + stderrTmp.getRemote() + " 1>&2"; // pipe stdout to stderr to avoid any buffering

                        final DurableTask task;
                        if ( launcher.isUnix() ) {
                            task = new BourneShellScript(commandString);
                        } else {
                            task = new WindowsBatchScript(commandString);
                        }

                        // Without this intervention, Durable task logs some extraneous details I don't want appearing in the console
                        // e.g. "[_OcWatch] Running shell script"
                        QuietTaskListenerFactory.QuietTasklistener quiet = QuietTaskListenerFactory.build(listener);
                        Controller dtc = task.launch(envVars,filePath,launcher,quiet);

                        try {
                            long reCheckSleep = 250;
                            boolean firstPass = true;
                            long outputSize = 0;
                            short tries = 0;

                            do {

                                byte[] newOutput;
                                try (InputStream is = stderrTmp.readFromOffset( outputSize )) {
                                    newOutput = IOUtils.toByteArray( is );
                                }
                                outputSize += newOutput.length;

                                if ( newOutput.length > 0 || firstPass ) {
                                    firstPass = false;
                                    reCheckSleep = Math.max( 250, reCheckSleep / 2 );

                                    listener.getLogger().println( "Running watch closure body" );
                                    // oc errors like "Unable to connect to the server: net/http: TLS handshake timeout" currently can only be
                                    // deciphered from the exceptions messages (typically within a java.util.concurrent.ExecutionException caused by
                                    // hudson.AbortException chain) as the exception types are generic and the rc are always 1;
                                    // For now, we are applying a generic and conservative retry approach
                                    try {
                                        Object o = getContext().newBodyInvoker().start().get(); // Run body and get result
                                        if ( o instanceof Boolean == false ) {
                                            getContext().onFailure(new ClassCastException("watch body return value " + o + " is not boolean"));
                                        }
                                        if ( (Boolean)o ) {
                                            listener.getLogger().println( "watch closure returned true; terminating watch" );
                                            dtc.stop(filePath,launcher);
                                            break master;
                                        }

                                        continue;
                                    } catch (Throwable t) {
                                        LOGGER.log(Level.FINE, "run", t);
                                        tries++;
                                        if (tries > 2)
                                            throw t;
                                        String exceptionMsgs = t.getMessage();
                                        if (t.getCause() != null)
                                            exceptionMsgs = exceptionMsgs + "; " + t.getCause().getMessage();
                                        listener.getLogger().println(String.format("\nAn exception occurred invoking 'oc' against the OpenShift master.  The operation will be retried.  Exception message \"%s\".\n", exceptionMsgs));
                                        Thread.sleep(125);
                                        
                                    }
                                }

                                // Gradually check less frequently if watch is not generating output
                                reCheckSleep = Math.min( 10000, (int)(reCheckSleep * 1.2f) );
                                listener.getLogger().println( "Checking watch output again in " + reCheckSleep + "ms" );
                                Thread.sleep( reCheckSleep );

                            } while ( (exitStatus = exitStatusRaceConditionBugWorkaround(dtc, filePath,launcher)) == null );

                        } finally {
                            dtc.cleanup(filePath);
                        }

                        // Reaching this point means that the watch terminated - exitStatus will not be null
                        if ( exitStatus.intValue() != 0 ) {
                            // Looks like the watch command encountered an error
                            throw new AbortException( "watch terminated with an error: " + exitStatus );
                        }
                    }

                } finally {
                    stderrTmp.delete();
                }

            } catch ( RuntimeException re ) {
                throw re;
            } catch ( Exception e ) {
                getContext().onFailure(e);
            }

            return null;
        }

    }

}
