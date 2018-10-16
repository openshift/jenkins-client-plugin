package com.openshift.jenkins.plugins.util;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Proc;
import hudson.Util;
import hudson.remoting.FastPipedInputStream;
import hudson.remoting.FastPipedOutputStream;
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.VirtualChannel;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.jenkinsci.remoting.RoleChecker;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/***
 * {@link ClientCommandRunner} runs `oc` on a slave
 */
public class ClientCommandRunner {
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
     * a {@link OutputObserver} will be notified when {@link ClientCommandRunner} reads a newline from stdout or stderr of the running oc process
     */
    public interface OutputObserver {
        /***
         * This method will be called every time the ClientCommandRunner reads a line from the stdout/stderr from the remote `oc` process.
         * @param line a line of output
         * @return true to indicate the ClientCommandRunner to interrupt the `oc` process immediately.
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
     * @param stdoutOutputObserver a {@link OutputObserver} that will be notified whenever {@link ClientCommandRunner} reads a line from stdout of `oc` process
     * @param stderrOutputObserver a {@link OutputObserver} that will be notified whenever {@link ClientCommandRunner} reads a line from stderr of `oc` process
     */
    public ClientCommandRunner(String[] command, FilePath filePath, EnvVars envVars, OutputObserver stdoutOutputObserver, OutputObserver stderrOutputObserver) {
        this.command = command;
        this.filePath = filePath;
        this.envVars = envVars;
        this.stdoutOutputObserver = stdoutOutputObserver;
        this.stderrOutputObserver = stderrOutputObserver;
    }

    private static class OcOutputConsumer implements Callable<Boolean> {
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

    private static class OcCallable implements FilePath.FileCallable<Integer> {
        private String[] command;
        private EnvVars envVars;
        private OutputStream out;
        private OutputStream err;

        public OcCallable(String[] command, EnvVars envVars, RemoteOutputStream out, RemoteOutputStream err) {
            this.command = command;
            this.envVars = envVars;
            this.out = out;
            this.err = err;
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
        }

        @Override
        public Integer invoke(File currentDir, VirtualChannel channel) throws IOException, InterruptedException {
            Proc.LocalProc proc = null;
            try (OutputStream out = this.out; OutputStream err = this.err) {
                // per explanations like https://stackoverflow.com/questions/10035383/setting-the-environment-for-processbuilder
                // even with propagating updates to the PATH env down to the creations of Proc.LocalProc and ProcessBuilder, 
                // they don't even effect the environment in which the ProcessBuilder is running. So to find the `oc` when the 
                // PATH is updated via the 'tool' step, we need to either 
                // 1) prepend the right dir from the path for 'oc'
                // 2) invoke a shell that then launches the actual 'oc' command, where we update the PATH prior
                // Our use of the jenkins durable task and bourne/windows scripts did 2), but because of 
                // https://bugzilla.redhat.com/show_bug.cgi?id=1625518 we analyze the PATH env var and do 1)
                String path = envVars.get("PATH");
                // default if running the openshift jenkins images
                String dirToUse = "/bin/";
                if (path == null || path.length() == 0) {
                    LOGGER.warning("PATH not properly set prior to invocation of 'oc'");
                } else {
                    String[] dirs = path.split(File.pathSeparator);
                    for (String dir : dirs) {
                        if (new File(dir, "oc").canExecute() || new File(dir, "oc.exe").canExecute()) {
                            dirToUse = dir.trim();
                            break;
                        }
                    }
                }
                // sanity check, make sure oc is our first command, though it always should be
                if (command[0].trim().equals("oc") || command[0].trim().equals("oc.exe")) {
                    command[0] = dirToUse + File.separator + command[0].trim();
                }
                
                proc = new Proc.LocalProc(command, Util.mapToEnv(envVars), null, out, err, currentDir);
                return proc.join();
            } finally {
                if (proc != null && proc.isAlive())
                    proc.kill();
            }
        }
    }

    /***
     * Run `oc` on a slave and wait for it to be exit
     * @return the exit status code of `oc`
     * @throws IOException when error reading stdin/stdout or calling the observers
     * @throws InterruptedException when the reading threads are interrupted
     */
    public int run() throws IOException, InterruptedException, ExecutionException {
        List<Future<Boolean>> ocOutputConsumerFutures = new ArrayList<>(2);
        Future<Integer> remoteOCProcessFuture = null;
        try (FastPipedOutputStream stdout = new FastPipedOutputStream();
             InputStream redirectedStdout = new FastPipedInputStream(stdout);
             FastPipedOutputStream stderr = new FastPipedOutputStream();
             InputStream redirectedStderr = new FastPipedInputStream(stderr)) {
            // running `oc` remotely
            // NOTE: Launcher is not used to start a remote process because of Jenkins bugs described on https://issues.jenkins-ci.org/browse/JENKINS-53586
            //   and https://issues.jenkins-ci.org/browse/JENKINS-53422.
            remoteOCProcessFuture = filePath.actAsync(new OcCallable(command, envVars, new RemoteOutputStream(stdout), new RemoteOutputStream(stderr)));

            // reading the output (stdout and stderr) from the remote `oc` process
            CompletionService<Boolean> completionService = new ExecutorCompletionService<>(pool);

            // handling stderr
            ocOutputConsumerFutures.add(completionService.submit(new OcOutputConsumer(redirectedStderr, stderrOutputObserver)));

            // handling stdout
            ocOutputConsumerFutures.add(completionService.submit(new OcOutputConsumer(redirectedStdout, stdoutOutputObserver)));

            // waiting for output handlers to stop
            for (int i = 0; i < ocOutputConsumerFutures.size(); ++i) {
                boolean shouldInterrupt = completionService.take().get();
                if (shouldInterrupt) { // an observer requests interrupting the remote `oc` process
                    remoteOCProcessFuture.cancel(true);
                }
            }
            // waiting for `oc` to stop and return the exit status.
            if (remoteOCProcessFuture.isCancelled())
                return -1; // indicates the process is interrupted by an OutputObserver
            else
                return remoteOCProcessFuture.get();
        } finally {
            // ensuring the `oc` process is terminated
            if (remoteOCProcessFuture != null && !remoteOCProcessFuture.isDone()) {
                remoteOCProcessFuture.cancel(true);
            }
            // ensuring all output handlers are stopped
            for (Future<Boolean> future : ocOutputConsumerFutures) {
                if (!future.isDone())
                    future.cancel(true);
            }
        }
    }
}
