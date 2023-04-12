/*
 * Copyright © 2016 Red Hat, Inc. (https://www.redhat.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
                advArgs, verbArgs, userArgs, options, token, logLevel, false);
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

        public Void run() throws IOException, InterruptedException {
            if (filePath != null && !filePath.exists()) {
                filePath.mkdirs();
            }
            getContext().saveState();
            listener.getLogger().println("Entering watch");

            final StringBuffer stderr = new StringBuffer();
            final boolean[] watchSuccess = {false};

            String commandString = step.cmdBuilder.asString(false);
            String[] command = QuotedStringTokenizer.tokenize(commandString);
            command = ClientCommandBuilder.fixPathInCommandArray(command, envVars, listener, filePath, launcher, step.watchLoglevel > 0);
            final TaskListener listener = this.listener;
            final int[] jsonRetries = {0};
            ClientCommandRunner runner = new ClientCommandRunner(command, filePath, envVars,
                    line -> { // got a line from stdout
                        if (step.watchLoglevel > 0) {
                            listener.getLogger().println("Received verbose watch output>>>");
                            listener.getLogger().println(line);
                            listener.getLogger().println("<<<");
                        }
                        for (; ; ) {
                            listener.getLogger().println("Running watch closure body");
                            try {
                                // Run body and get result
                                Object o = getContext().newBodyInvoker().start().get();

                                // If the watch body returns a Boolean and it is true, time to exit
                                if (o instanceof Boolean && (Boolean) o) {
                                    watchSuccess[0] = true;
                                    listener.getLogger().println("\nwatch closure returned true; terminating watch");
                                    return true; // interrupt `oc`
                                }
                                listener.getLogger().println("watch closure returned " + o);
                                return false; // don't interrupt `oc`
                            } catch (groovy.json.JsonException ex) {
                                // FIXME: We've seen instances where if the watch closer subsequently calls oc and it processes output
                                // before oc has finished updating it we can get json formatting
                                // exceptions processing the output ... we can retry here
                                if (jsonRetries[0]++ >= 5) {
                                    throw ex; // give up retrying
                                }
                                listener.getLogger().println("watch closer got json formatting exception, trying again");
                            } catch (InterruptedException tie) { // timeout{} block interrupted us
                                listener.getLogger().println("\nwatch closure interrupted (timeout?)");
                                throw tie;
                            } catch (Throwable t) {
                                String exceptionMsgs = t.getMessage();
                                if (t.getCause() != null) {
                                    exceptionMsgs = exceptionMsgs + "; " + t.getCause().getMessage();
                                }
                                listener.getLogger().println(String.format("\nwatch closure threw an exception: \"%s\".\n", exceptionMsgs));
                                throw new IOException(t);
                            }
                        }
                    },
                    line -> { // got a line from stderr
                        stderr.append(line).append('\n');
                        listener.getLogger().println("Received error output>>>");
                        listener.getLogger().println(line);
                        listener.getLogger().println("<<<");
                        return false; // don't interrupt `oc`
                    });
            long reWatchSleep = 250;
            try {
                for (; ; ) {
                    int exitStatus = runner.run(launcher);
                    if (watchSuccess[0])
                        break;
                    if (exitStatus != 0) {
                        String msg = "OpenShift Client exited with status code " + Integer.toString(exitStatus)
                                + ", command: " + step.cmdBuilder.buildCommand(true)
                                + ", stderr: " + stderr.toString().trim();
                        throw new AbortException(msg);
                    }
                    listener.getLogger().println("Checking watch output and running watch closure again in " + reWatchSleep + "ms");
                    Thread.sleep(reWatchSleep);
                    if (reWatchSleep < 10000) { // Gradually re-watch less frequently
                        reWatchSleep *= 1.2f;
                    }
                }
            } catch (Throwable e) {
                getContext().onFailure(e);
            }
            return null;
        }
    }
}
