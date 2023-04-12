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
package com.openshift.jenkins.plugins.util;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.remoting.FastPipedInputStream;
import hudson.remoting.FastPipedOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;

import javax.annotation.Nonnull;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/***
 * {@link ClientCommandRunner} runs `oc` on a slave
 */
public class ClientCommandRunner implements Serializable {
    private static final long serialVersionUID = 42L;
    private static final Logger LOGGER = Logger.getLogger(ClientCommandRunner.class.getName());
    // creating a thread pool for output consumers
    private static Executor pool;
    static {
        String poolSize = System.getenv("OPENSHIFT_CLIENT_PLUGIN_EXECUTOR_POOL_SIZE");
        int ps = 25;
        try {
            if (poolSize != null && poolSize.trim().length() > 0)
                ps = Integer.parseInt(poolSize);
        } catch (Throwable t ) {
            LOGGER.log(Level.WARNING, "ClientCommandRunner", t);
        }
        pool = Executors.newFixedThreadPool(ps);
    }

    /***
     * a {@link OutputObserver} will be notified when {@link ClientCommandRunner} reads a new line from stdout or stderr of the running oc process
     */
    public interface OutputObserver {
        /***
         * This method will be called every time the ClientCommandRunner reads a line from the stdout/stderr from the remote `oc` process.
         * @param line a line of output
         * @return true to indicate the ClientCommandRunner to interrupt the `oc` process immediately.
         * @throws IOException when I/O error
         * @throws InterruptedException when the reading threads are interrupted
         */
        boolean onReadLine(String line) throws IOException, InterruptedException;
    }

    private String[] command;
    private FilePath filePath;
    private EnvVars envVars;
    private OutputObserver stdoutOutputObserver;
    private OutputObserver stderrOutputObserver;

    /***
     * Create a new {@link ClientCommandRunner} instance.
     * @param command command to run OpenShift client tool
     * @param filePath current directory
     * @param envVars environment variables
     * @param stdoutOutputObserver a {@link OutputObserver} that will be notified whenever {@link ClientCommandRunner} reads a line from stdout of `oc` process
     * @param stderrOutputObserver a {@link OutputObserver} that will be notified whenever {@link ClientCommandRunner} reads a line from stderr of `oc` process
     */
    public ClientCommandRunner(@Nonnull String[] command, @Nonnull FilePath filePath, @Nonnull EnvVars envVars,
                               @Nonnull OutputObserver stdoutOutputObserver, @Nonnull OutputObserver stderrOutputObserver) {
        this.command = command;
        this.filePath = filePath;
        this.envVars = envVars;
        this.stdoutOutputObserver = stdoutOutputObserver;
        this.stderrOutputObserver = stderrOutputObserver;
    }

    private static class OcOutputConsumer implements Callable<Object> {
        private InputStream in;
        private OutputObserver outputObserver;

        public OcOutputConsumer(InputStream in, OutputObserver outputObserver) {
            this.in = in;
            this.outputObserver = outputObserver;
        }

        @Override
        public Boolean call() throws IOException, InterruptedException {
            try (Reader reader = new InputStreamReader(in)) {
                LineIterator it = IOUtils.lineIterator(reader);
                while (it.hasNext()) {
                    String line = it.nextLine();
                    if (outputObserver.onReadLine(line)) {
                        return true; // interrupted by OutputObserver
                    }
                }
            }
            return false;
        }
    }

    /***
     * Run `oc` on a slave and wait for it to be exit
     * @param launcher Launcher for launching a remote process
     * @return the exit status code of `oc`
     * @throws IOException when error reading stdin/stdout or calling the observers
     * @throws InterruptedException when the reading threads are interrupted
     * @throws ExecutionException error when executing closures of observers
     */
    public int run(@Nonnull Launcher launcher) throws IOException, InterruptedException, ExecutionException {
        CompletionService<Object> completionService = new ExecutorCompletionService<>(pool);
        Proc proc = null;
        int exitStatus = -1;
        List<Future<Object>> futures = new ArrayList<>(3);
        try (FastPipedOutputStream stdout = new FastPipedOutputStream();
             FastPipedInputStream redirectedStdout = new FastPipedInputStream(stdout);
             FastPipedOutputStream stderr = new FastPipedOutputStream();
             FastPipedInputStream redirectedStderr = new FastPipedInputStream(stderr)) {
            // running `oc` remotely
            Launcher.ProcStarter ps = launcher.launch().cmds(Arrays.asList(command)).envs(envVars).pwd(filePath).quiet(true).stdout(stdout).stderr(stderr);

            // handling stderr
            futures.add(completionService.submit(new OcOutputConsumer(redirectedStderr, stderrOutputObserver)));

            // handling stdout
            futures.add(completionService.submit(new OcOutputConsumer(redirectedStdout, stdoutOutputObserver)));

            // start remote `oc` process
            proc = ps.start();
            final Proc proc1 = proc; // make it "final"
            // future to wait for remote `oc` process to stop
            Future<Object> procFuture = completionService.submit(() -> proc1.join());
            futures.add(procFuture);

            // waiting for any of oc process, stdout consumer, and stderr consumer to stop
            for (int i = 0; i < futures.size(); ++i) {
                Future<Object> completedFuture = completionService.take();
                if (completedFuture == procFuture) {
                    // the remote `oc` process has exited
                    exitStatus = (int) completedFuture.get();
                    // closing stdout/stderr so that observers will stop
                    stdout.close();
                    stderr.close();
                    redirectedStdout.close();
                    redirectedStderr.close();
                } else if ((Boolean) completedFuture.get()) {
                    // an observer requests interrupting the remote `oc` process
                    proc.kill();
                }
            }
            return exitStatus;
        } finally {
            // ensuring the remote `oc` process is terminated
            if (proc != null)
                proc.kill();
            // ensuring all futures are stopped
            for (Future<Object> future : futures) {
                if (!future.isDone())
                    future.cancel(true);
            }
        }
    }
}
