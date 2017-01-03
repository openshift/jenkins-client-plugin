package com.openshift.jenkins.plugins.util;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class OcCmdBuilder implements Serializable {

    public final String server;
    public final String project;
    public final String verb;
    protected final List verbArgs;
    protected final List userArgs;
    protected final List options;
    protected final String token;
    protected final List verboseOptions;
    public final int logLevel;


    public OcCmdBuilder(String server, String project, String verb, List verbArgs, List userArgs, List options, List verboseOptions, String token, int logLevel ) {
        this.server = server;
        this.project = project;
        this.verb = verb==null?"help":verb;
        this.verbArgs = verbArgs;
        this.userArgs = userArgs;
        this.options = options;
        this.token = token;
        this.logLevel = logLevel;
        this.verboseOptions = verboseOptions;
    }

    private static List fixNull(List l) {
        if ( l == null ) {
            return new ArrayList(0);
        }
        return l;
    }

    private boolean hasArg( List<String> args, String... argsToFind ) {
        for ( String arg : args ) {
            for ( String atf : argsToFind ) {
                if ( arg.equals( atf ) || arg.startsWith( atf+"=" ) ) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Builds the command line to invoke.
     * @param redacted Requests the command line be constructed for logging purposes. Sensitive
     *                 information will be stripped. Verbose information wil be stripped unless
     *                 we are in logLevel mode.
     * @return A string representing the command to invoke.
     */
    public String build(boolean redacted ) {
        ArrayList<String> args = new ArrayList<String>();

        args.add( verb );

        fixNull(userArgs).forEach( e -> args.add( e.toString() ) );

        fixNull(verbArgs).forEach( e -> args.add( e.toString() ) );

        fixNull(options).forEach( e -> args.add( e.toString() ) );

        if ( this.server != null ) {
            args.add("--server=" + server );
        }

        if ( this.project != null ) {
            if ( !hasArg( args, "-n", "--namespace" ) ) { // only set namespace if user has not supplied it directly
                args.add("--namespace=" + project );
            }
        }

        // Some arguments may be long and provide little value (e.g. the path of the server CA),
        // so hide them unless we are in logLevel mode.
        if ( !redacted || logLevel>0) {
            fixNull(verboseOptions).forEach( e -> args.add( e.toString() ) );
            if ( !hasArg(args,"--loglevel")) {
                args.add( "--loglevel=" + logLevel );
            }
        }

        String token = this.token;
        if ( redacted && token != null ) {
            token = "XXXXX";
        }

        if ( token != null ) {
            if ( !hasArg( args, "--token" ) ) { // only set if not specified
                args.add("--token=" + token);
            }
        }

        StringBuilder sb = new StringBuilder( "oc " );
        for ( String arg : args ) {
            sb.append( arg );
            sb.append( " " );
        }
        return sb.toString();
    }


}
