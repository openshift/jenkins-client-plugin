package com.openshift.jenkins.plugins.util;

import com.openshift.jenkins.plugins.OpenShift;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class ClientCommandBuilder implements Serializable {
    
    private static Logger LOGGER = Logger.getLogger(ClientCommandBuilder.class.getName()); 

    public final String server;
    public final String project;
    public final boolean skipTLSVerify;
    public final String caPath;
    public final String verb;
    public final List advArgs;
    protected final List verbArgs;
    protected final List userArgs;
    protected final List options;
    protected final String token;
    public final int logLevel;

    public ClientCommandBuilder(String server, String project, boolean skipTLSVerify, String caPath,
            String verb, List advArgs, List verbArgs, List userArgs, List options,
            String token, int logLevel) {
        if (token != null && (token.contains("\r") || token.contains("\n")))
            throw new IllegalArgumentException(
                    "tokens cannot contain carriage returns or new lines");
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
    }
    
    private String dealWithQuotes(String s) {
        // So, if the arg that comes in either 
        // a) has neither ' or "", or
        // b) has ", but doesn not start with it, and does not start with '
        // then wrapper with '
        if ((!s.contains("'") && !s.contains("\"")) ||
           (!s.startsWith("\"") && !s.startsWith("'") && s.contains("\""))) {
            s = "'" + s + "'";
        // if the arg that comes in either 
        // c) has ', but does not start with it, and does not start with "
        // then wrapper with "
        } else if (!s.startsWith("\"") && !s.startsWith("'") && s.contains(("'"))) {
            s = "\"" + s + "\"";
        }
        
        // also, I have  confirmed throught testing that the jenkins groovy
        // parser does not allow settings like '...'...'; it has to be '..."...'
        // or "....'..."
        return s;
    }

    private List<String> toStringArray(List l) {
        ArrayList<String> n = new ArrayList<String>();
        if (l == null) {
            return n;
        }
        for (Object o : l) {
            String s = o.toString();
            if (wrapperInQuotes() && s.contains(" ")) {
                // the Jenkins QuotedStringTokenizer is not quite 
                // copacetic with template parameters defined in 
                // groovy variables (specifically strings where you have spaces but don't have 
                // to encapsulate with " or ').
                // 
                // for example:
                /*
                def asdf = 'all users'
                def param = ["-p", "POSTGRESQL_USER=${asdf}"]
                def sel = openshift.selector("templates/postgresql-ephemeral")
                def temp = sel.object()
                def objs = openshift.process(temp, param)                 
                 */
                // we do *NOT* want ot force them to say 
                // def param = ["-p", "POSTGRESQL_USER='${asdf}'"]
                
                // that said, for now at least, we'll try to use the Jenkins
                // QuotedStringTokenizer for the other " and ' scenarios, especially around exec/rsh
                // where the entire command might be listed as 'ps ax' vs. 'ps','ax'
                // but still adjust
                // the input with the logic here so that it 
                // properly handles the groovy string vars with spaces scenario 
                // correctly.
                
                // however, we also have to worry about the user specifying multiple -p=<name>=<value>
                // settings in one string ... template parsing will get confused in this case and 
                // miss any -p settings after the first one ... if those params are not required,
                // it will just ignore the param setting (yikes)
                // if those params are required, then the template processing notes a required param
                // is missing (sheeesh)
                String[] params1 = s.trim().split("-p=");
                String[] params2 = s.trim().split("-p ");
                if (params1.length > 1) {
                    for (String p : params1) {
                        if (p.trim().length() > 0) {
                            if (p.trim().contains(" "))
                                p = dealWithQuotes(p);
                            n.add("-p=" + p.trim());
                        }
                    }
                } else if (params2.length > 1) {
                    for (String p : params2) {
                        if (p.trim().length() > 0) {
                            if (p.trim().contains(" "))
                                p = dealWithQuotes(p);
                            n.add("-p " + p.trim());
                        }
                    }
                } else {
                    s = dealWithQuotes(s);
                    n.add(s);
                }                
                
            } else {
                n.add(s);
            }
        }
        return n;
    }

    private boolean hasArg(List<String> args, String... argsToFind) {
        for (String arg : args) {
            for (String atf : argsToFind) {
                if (arg.equals(atf) || arg.startsWith(atf + "=")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Builds the command line to invoke.
     *
     * @param redacted
     *            Requests the command line be constructed for logging purposes.
     *            Sensitive information will be stripped. Verbose information
     *            wil be stripped unless we are in logLevel mode.
     * @return A list of command line arguments for the 'oc' command.
     */
    public List<String> buildCommand(boolean redacted) {
        ArrayList<String> cmd = new ArrayList<String>();

        String toolName = (new OpenShift.DescriptorImpl()).getClientToolName();
        cmd.add(toolName);

        // in general with 'oc' having arguments like --server or --namespace precede the verb helps
        // with some of the exec/rsh type scenarios ....oc rsh in particular was
        // confusing args like --server as arguments into the command the user was
        // trying to execute in the target pod, etc.
        if (this.server != null) {
            cmd.add("--server=" + server);
        }

        cmd.addAll(toStringArray(this.advArgs));

        if (this.skipTLSVerify) {
             cmd.add("--insecure-skip-tls-verify");
        } else {
            if (this.caPath != null) {
                cmd.add("--certificate-authority=" + this.caPath);
            }
        }

        if (this.project != null) {
            if (!hasArg(cmd, "-n", "--namespace")) { // only set namespace if
                                                     // user has not supplied it
                                                     // directly
                cmd.add("--namespace=" + project);
            }
        }

        // Some arguments may be long and provide little value (e.g. the path of
        // the server CA),
        // so hide them unless we are in logLevel mode.
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
