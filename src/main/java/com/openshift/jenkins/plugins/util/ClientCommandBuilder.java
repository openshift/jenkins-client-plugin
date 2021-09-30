package com.openshift.jenkins.plugins.util;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.openshift.jenkins.plugins.OpenShift;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.LocalLauncher;
import hudson.Launcher.RemoteLauncher;
import hudson.Platform;
import hudson.model.TaskListener;
import jenkins.security.MasterToSlaveCallable;

public class ClientCommandBuilder implements Serializable {

	private static final long serialVersionUID = -631237029459897399L;

	private final static Logger LOGGER = Logger.getLogger(ClientCommandBuilder.class.getName());

	public final String server;
	public final String project;
	public final boolean skipTLSVerify;
	public final String caPath;
	public final String verb;
	public final List<String> advArgs;
	protected final List<String> verbArgs;
	protected final List<String> userArgs;
	protected final List<String> options;
	protected final String token;
	public final int logLevel;
	public final boolean streamStdOutToConsolePrefix;

	// start borrowed from BourneShellScript
	public static enum OsType {
		DARWIN, UNIX, WINDOWS, ZOS
	}

	public static final class getOsType extends MasterToSlaveCallable<OsType, RuntimeException> {
		@Override
		public OsType call() throws RuntimeException {
			return getOsFromPlatform();
		}

		private static final long serialVersionUID = 1L;
	}

	public static OsType getOsFromPlatform() {
		if (Platform.isDarwin()) {
			return OsType.DARWIN;
		} else if (Platform.current() == Platform.WINDOWS) {
			return OsType.WINDOWS;
		} else if (Platform.current() == Platform.UNIX && System.getProperty("os.name").equals("z/OS")) {
			return OsType.ZOS;
		} else {
			return OsType.UNIX; // Default Value
		}
	}

	// end borrowed from BourneShellScript
	public static String[] fixPathInCommandArray(String[] command, EnvVars envVars, TaskListener listener,
			FilePath filePath, Launcher launcher, boolean verbose)
			throws IOException, InterruptedException, RuntimeException {
		/*
		 * per explanations like
		 * https://stackoverflow.com/questions/10035383/setting-the-environment-for-
		 * processbuilder even with propagating updates to the PATH env down to the
		 * creations of Proc.LocalProc and ProcessBuilder, they don't even effect the
		 * environment in which the ProcessBuilder is running. So to find the `oc` when
		 * the PATH is updated via the 'tool' step, we need to either 1) prepend the
		 * right dir from the path for 'oc' 2) invoke a shell that then launches the
		 * actual 'oc' command, where we update the PATH prior Our use of the jenkins
		 * durable task and bourne/windows scripts did 2), but because of
		 * https://bugzilla.redhat.com/show_bug.cgi?id=1625518 we analyze the PATH env
		 * var and do 1)
		 */

		OsType targetType = filePath.act(new getOsType());
		String path = envVars.get("PATH");
		List<String> foundOcs = new ArrayList<String>();
		FindOC finder = new FindOC(path);
		try {
			foundOcs = filePath.act(finder);
		} catch (Throwable t) {
			t.printStackTrace(listener.getLogger());
			return command;
		}
		if (foundOcs == null || foundOcs.size() == 0) {
			if (verbose)
				listener.getLogger()
						.println("could not find oc binary on the target computer of OS type " + targetType);
			if (!(launcher instanceof RemoteLauncher) || !(launcher instanceof LocalLauncher)) {
				if (verbose)
					listener.getLogger().println("but your launcher is of a type that might have hindered out scan");
			}
		} else {
			if (verbose)
				listener.getLogger().println("found the following oc executables on the target computer of OS type "
						+ targetType + ": " + foundOcs);
		}
		if (foundOcs.size() == 0) {
			return command;
		}
		command[0] = foundOcs.get(0);
		return command;
	}

	public ClientCommandBuilder(String server, String project, boolean skipTLSVerify, String caPath, String verb,
			List<String> advArgs, List<String> verbArgs, List<String> userArgs, List<String> options, String token,
			int logLevel, boolean streamStdOutToConsolePrefix) {
		if (token != null && (token.contains("\r") || token.contains("\n")))
			throw new IllegalArgumentException("tokens cannot contain carriage returns or new lines");
		this.server = server;
		this.project = project;
		this.skipTLSVerify = skipTLSVerify;
		this.caPath = caPath;
		this.verb = verb == null ? "help" : verb;
		this.advArgs = advArgs;
		this.verbArgs = verbArgs;
		this.userArgs = userArgs;
		this.options = options;
		this.token = token;
		this.logLevel = logLevel;
		this.streamStdOutToConsolePrefix = streamStdOutToConsolePrefix;
	}

	private String dealWithQuotes(String s) {
		// So, if the arg that comes in either
		// a) has neither ' or "", or
		// b) has ", but doesn not start with it, and does not start with '
		// then wrapper with '
		if ((!s.contains("'") && !s.contains("\""))
				|| (!s.startsWith("\"") && !s.startsWith("'") && s.contains("\""))) {
			s = "'" + s + "'";
			// if the arg that comes in either
			// c) has ', but does not start with it, and does not start with "
			// then wrapper with "
		} else if (!s.startsWith("\"") && !s.startsWith("'") && s.contains(("'"))) {
			s = "\"" + s + "\"";
		}

		// also, I have confirmed throught testing that the jenkins groovy
		// parser does not allow settings like '...'...'; it has to be '..."...'
		// or "....'..."
		return s;
	}

	private List<String> toStringArray(List list) {
		ArrayList<String> array = new ArrayList<String>();
		if (list == null) {
			return array;
		}
		ArrayList<String> listCopy = new ArrayList<String>(list);
		for (int i = 0; i < listCopy.size(); i++) {
			String element = listCopy.get(i);
			if (element != null && element.trim().length() == 0) {
				// skip entry presumably blanked by our -f processing below
				continue;
			}
			int nextIndex = i + 1;
			if (element.trim().equals("-f") && nextIndex < listCopy.size() && !this.streamStdOutToConsolePrefix) {
				array.add(element);
				// this means we have a -f <tmp file name>, where the tmp file name
				// now includes the job name, which can have spaces
				String fileModifier = listCopy.get(nextIndex);
				if (fileModifier.trim().length() > 0 && fileModifier.trim().contains(" ")) {
					array.add("\"" + fileModifier + "\"");
					// blank out entry with space so we can skip it next time
					listCopy.set(nextIndex, "");
				}
			} else {
				/**
				 * the Jenkins QuotedStringTokenizer is not quite copacetic with template
				 * parameters defined in groovy variables (specifically strings where you have
				 * spaces but don't have to encapsulate with " or ').
				 * 
				 * for example: def asdf = 'all users' <br/>
				 * def param = ["-p", "POSTGRESQL_USER=${asdf}"] <br/>
				 * def sel = openshift.selector("templates/postgresql-ephemeral") <br/>
				 * def temp = sel.object() <br/>
				 * def objs = openshift.process(temp, param) <br/>
				 * we do *NOT* want ot force them to say def param = ["-p",
				 * "POSTGRESQL_USER='${asdf}'"] <br/>
				 * 
				 * that said, for now at least, we'll try to use the Jenkins
				 * QuotedStringTokenizer for the other " and ' scenarios, especially around
				 * exec/rsh where the entire command might be listed as 'ps ax' vs. 'ps','ax'
				 * but still adjust the input with the logic here so that it properly handles
				 * the groovy string vars with spaces scenario correctly. however, we also have
				 * to worry about the user specifying multiple -p=<name>=<value> settings in one
				 * string ... template parsing will get confused in this case and miss any -p
				 * settings after the first one ... if those params are not required, it will
				 * just ignore the param setting (yikes) if those params are required, then the
				 * template processing notes a required param is missing (sheeesh)
				 **/
				if (wrapperInQuotes() && element.contains(" ")) { // this handle the case of 'oc process '

					String[] params1 = element.trim().split("-p=");
					String[] params2 = element.trim().split("-p ");
					if (params1.length > 1) {
						for (String p : params1) {
							if (p.trim().length() > 0) {
								if (p.trim().contains(" "))
									p = dealWithQuotes(p);
								array.add("-p=" + p.trim());
							}
						}
					} else if (params2.length > 1) {
						for (String p : params2) {
							if (p.trim().length() > 0) {
								if (p.trim().contains(" "))
									p = dealWithQuotes(p);
								array.add("-p " + p.trim());
							}
						}
					} else {
						element = dealWithQuotes(element);
						array.add(element);
					}

				} else {
					array.add(element);
				}
			}
		}
		return array;
	}

	@Deprecated
	/**
	 * use hasArg(String...argsToFind)
	 * 
	 * @param args
	 * @param argsToFind
	 * @return
	 * @deprecated
	 */
	private boolean hasArg(List<String> args, String... argsToFind) {
		if (args != null) {
			for (String arg : args) {
				if (argsToFind != null) {
					for (String atf : argsToFind) {
						if (arg.equals(atf) || arg.startsWith(atf + "=")) {
							return true;
						}
					}
				}
			}
		}
		return false;

	}

	private boolean hasArg(String... argsToFind) {
		return this.hasArg(this.advArgs, argsToFind) || this.hasArg(this.userArgs, argsToFind)
				|| this.hasArg(this.verbArgs, argsToFind);
	}

	/**
	 * Builds the command line to invoke.
	 *
	 * @param redacted Requests the command line be constructed for logging
	 *                 purposes. Sensitive information will be stripped. Verbose
	 *                 information wil be stripped unless we are in logLevel mode.
	 * @return A list of command line arguments for the 'oc' command.
	 */
	public List<String> buildCommand(boolean redacted) {
		ArrayList<String> cmd = new ArrayList<String>();
		String toolName = (new OpenShift.DescriptorImpl()).getClientToolName();
		cmd.add(toolName);
		/*
		 * in general with 'oc' having arguments like --server or --namespace precede
		 * the verb helps with some of the exec/rsh type scenarios ....oc rsh in
		 * particular was confusing args like --server as arguments into the command the
		 * user was trying to execute in the target pod, etc.
		 */

		if (this.server != null) {
			cmd.add("--server=" + server);
		}

		cmd.addAll(toStringArray(this.advArgs));

		if (this.skipTLSVerify) {
			cmd.add("--insecure-skip-tls-verify");
		} else {
			if (this.caPath != null) {
				cmd.add("--certificate-authority=\"" + this.caPath + "\"");
			}
		}

		if (this.project != null) {
			if (!this.hasArg("-n", "--namespace")) {
				LOGGER.finest("No -n or --namespace=  find in all arguments. Project namespace will be set instead");
				// only set namespace if user has *not* supplied it directly
				cmd.add("--namespace=" + this.project);
			}
		}

		// Some arguments may be long and provide little value (e.g. the path of
		// the server CA), so hide them unless we are in logLevel mode.
		if (!redacted || logLevel > 0) {
			if (!hasArg(cmd, "--loglevel")) {
				cmd.add("--loglevel=" + logLevel);
			}
		}

		String token = this.token;
		if (redacted && token != null) {
			token = "XXXXX";
		}

		if (token != null) {
			if (!hasArg(cmd, "--token")) { // only set if not specified
				cmd.add("--token=" + token);
			}
		}

		cmd.add(verb);
		cmd.addAll(toStringArray(verbArgs));
		cmd.addAll(toStringArray(userArgs));
		cmd.addAll(toStringArray(options));
		return cmd;
	}

	public String asString(boolean redacted) {
		StringBuffer sb = new StringBuffer();
		for (String arg : buildCommand(redacted)) {
			sb.append(arg);
			sb.append(" ");
		}
		return sb.toString();
	}

	public String[] asStringArray(boolean redacted) {
		List<String> list = buildCommand(redacted);
		return list.toArray(new String[0]);
	}

	public boolean wrapperInQuotes() {
		if (verb.trim().equals("process"))
			return true;
		return false;
	}

}
