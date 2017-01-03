package com.openshift.jenkins.plugins.pipeline;

import com.openshift.jenkins.plugins.util.OcCmdBuilder;
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

public class OcWatch extends AbstractStepImpl {

    public static final String FUNCTION_NAME = "_OcWatch";

    private final OcCmdBuilder cmdBuilder;

    @DataBoundConstructor
    public OcWatch(String server, String project, String verb, List verbArgs, List userArgs, List options, List verboseOptions, String token, int logLevel ) {
        this.cmdBuilder = new OcCmdBuilder( server,project,verb,verbArgs,userArgs,options,verboseOptions,token,logLevel);
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


        /** Unused, just to force the descriptor to request it. */
        @StepContextParameter
        private transient TaskListener listener;

        public Void run() {
            getContext().saveState();
            listener.getLogger().println( "Entering watch" );

            try {

                FilePath stderrTmp = filePath.createTextTempFile( "watchstderr", ".txt", "", false );
                try {

                    master:
                    while( true ) {
                        String commandString = step.cmdBuilder.build(false);
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
                                }

                                // Gradually check less frequently if watch is not generating output
                                reCheckSleep = Math.min( 10000, (int)(reCheckSleep * 1.2f) );
                                listener.getLogger().println( "Checking watch output again in " + reCheckSleep + "ms" );
                                Thread.sleep( reCheckSleep );

                            } while ( dtc.exitStatus(filePath,launcher) == null );

                        } finally {
                            dtc.cleanup(filePath);
                        }

                        // Reaching this point means that the watch terminated
                        if ( dtc.exitStatus(filePath,launcher) != 0 ) {
                            // Looks like the watch command encountered an error
                            throw new AbortException( "watch terminated with an error: " + dtc.exitStatus(filePath,launcher) );
                        }
                    }

                } finally {
                    stderrTmp.delete();
                }

            } catch ( Exception e ) {
                getContext().onFailure(e);
            }

            return null;
        }

    }

}
