package com.openshift.jenkins.plugins

import com.cloudbees.groovy.cps.NonCPS
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.openshift.jenkins.plugins.pipeline.OcContextInit
import com.openshift.jenkins.plugins.pipeline.OcAction
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import hudson.AbortException
import hudson.FilePath
import hudson.Util


class OpenShiftDSL implements Serializable {

    private org.jenkinsci.plugins.workflow.cps.CpsScript script

    // Load the global config for OpenShift DSL
    private transient OpenShift.DescriptorImpl config = new OpenShift.DescriptorImpl();

    private int logLevel = 0; // Modified by calls to openshift.logLevel

    public OpenShiftDSL(org.jenkinsci.plugins.workflow.cps.CpsScript script) {
        this.script = script
    }

    private Context currentContext = null;

    private static final Map<String,String> abbreviations = [
            "svc" : "service",
            "p" : "pod",
            "po" : "pod",
            "bc" : "buildconfig",
            "is" : "imagestream",
            "rc" : "replicationcontroller",
            "dc" : "deploymentconfig" ]

    enum ContextId implements Serializable{
        WITH_CLUSTER("openshift.withCluster"), WITH_PROJECT("openshift.withProject"), DO_AS("openshift.doAs")
        private final String name;
        ContextId(String name) {
            this.@name = name;
        }
        public String toString() {
            return name;
        }
    }

    private class Context implements Serializable {

        protected final Context parent;
        private final OcContextInit.Execution exec;

        private String credentialsId;
        private String serverUrl;
        private String serverCertificateAuthorityPath;
        private Boolean skipTlsVerify;
        private String project;
        private ContextId id;

        private List<FilePath> destroyOnReturn = new ArrayList<FilePath>();

        protected Context( Context parent, ContextId id ) {
            this.@parent = parent;
            this.@id = id;
            this.@exec = script._OcContextInit();
        }

        public <V> V run( Closure<V> body ) {
            if ( destroyOnReturn == null ) {
                throw new IllegalStateException(this.getClass() + " has already been run once and cannot be used again");
            }
            Context lastContext = currentContext;
            currentContext = this;
            try {
                return body()
            } finally {
                currentContext = lastContext;
                destroyOnReturn.each{ fp -> fp.delete() }
                destroyOnReturn = null;
            }
        }

        public ContextId getContextId() {
            return this.@id;
        }

        public String getToken() {
            if ( this.@credentialsId != null ) {
                OpenShiftTokenCredentials tokenSecret = CredentialsProvider.findCredentialById( credentialsId, OpenShiftTokenCredentials.class, script.$build(), Collections.emptyList() );
                if ( tokenSecret != null ) {
                    return tokenSecret.getToken();
                }
                // Otherwise, assume that this is a literal/direct token value
                return this.@credentialsId;
            }

            // avoid asking outer contexts for credentials with withCluster( "named" ) { withCluster ( "https://..." ) { ... } }
            if ( this.@serverUrl == null && parent != null ) {
                return parent.getToken();
            }

            // Instruct the command builder to not pass a token. This should force oc to default to mounted secret information.
            // Specifically the env variable BEARER_TOKEN_FILE: "/run/secrets/kubernetes.io/serviceaccount/token"
            return null;
        }

        public void setCredentialsId(String credentialsId ) {
            this.@credentialsId = Util.fixEmptyAndTrim(credentialsId);
        }

        public String getProject() {
            if ( this.@project != null ) {
                return this.@project;
            }
            if ( parent != null ) {
                return parent.getProject();
            }

            return script.readFile("/run/secrets/kubernetes.io/serviceaccount/namespace");
        }

        public void setProject(String project ) {
            this.@project = Util.fixEmptyAndTrim(project);
        }

        public String getServerCertificateAuthorityPath() {
            if ( this.@serverCertificateAuthorityPath != null ) {
                return this.@serverCertificateAuthorityPath;
            }
            if ( parent != null ) {
                return parent.getServerCertificateAuthorityPath();
            }

            // Assume we are running in an OpenShift pod with a service account mounted
            return "/run/secrets/kubernetes.io/serviceaccount/ca.crt";
        }

        public void setServerCertificateAuthorityContent(String serverCertificateAuthorityContent ) {
            serverCertificateAuthorityContent = Util.fixEmptyAndTrim(serverCertificateAuthorityContent);
            if ( serverCertificateAuthorityContent != null ) {
                /**
                 * The certificate authority content must be written to the agent's file
                 * system. It would be nice if we could set the value in an environment variable
                 * instead.
                 */
                FilePath ca = exec.getWorkspaceFilePath().createTextTempFile("serverca", ".crt", serverCertificateAuthorityContent, false );
                destroyOnReturn.add( ca );
                this.@serverCertificateAuthorityPath = ca.getRemote();
            }
        }

        public String getServerUrl() {
            if ( this.@serverUrl != null ) {
                return this.@serverUrl;
            }
            if ( parent != null ) {
                return parent.getServerUrl();
            }
            if ( script.env.KUBERNETES_SERVICE_HOST == null ) {
                throw new IllegalStateException( "No cluster information specified and unable to find `KUBERNETES_MASTER` URL environment variable.");
            }
            if ( script.env.KUBERNETES_SERVICE_PORT_HTTPS == null ) {
                throw new IllegalStateException( "No cluster information specified and unable to find `KUBERNETES_SERVICE_PORT_HTTPS` URL environment variable.");
            }
            return "https://${script.env.KUBERNETES_SERVICE_HOST}:${script.env.KUBERNETES_SERVICE_PORT_HTTPS}";
        }

        public void setServerUrl( String serverUrl, boolean skipTlsVerify ) {
            this.@serverUrl = Util.fixEmptyAndTrim(serverUrl);
            this.@skipTlsVerify = skipTlsVerify;
        }

        public boolean isSkipTlsVerify() {
            if ( this.@skipTlsVerify != null ) {
                return this.@skipTlsVerify;
            }
            if ( parent != null ) {
                return parent.isSkipTlsVerify();
            }
            return false;
        }

    }

    /**
     * Returns true if the test context identifier is found within the context
     */
    private boolean contextContains( Context context, ContextId test ) {
        while ( context != null ) {
            if ( context.getContextId() == test ) {
                return true;
            }
            context = context.parent;
        }
        return false;
    }

    @NonCPS
    private void dieIfWithin( ContextId me, Context context, ContextId... forbidden ) throws AbortException {
        for ( ContextId forbid : forbidden ) {
            if ( contextContains( context, forbid ) ) {
                throw new AbortException( me.toString() + " cannot be used within a " + forbid.toString() + " closure body" );
            }
        }
    }

    @NonCPS
    private void dieIfWithout( ContextId me, Context context, ContextId required ) throws AbortException {
        if ( contextContains( context, required ) ) {
            throw new AbortException( me.toString() + " can only be used within a " + required.toString() + " closure body" );
        }
    }


    public String project() {
        return currentContext.getProject();
    }

    /**
     * @param name The name can be a literal URL for the cluster or,
     *          preferably, a Jenkins specific
     *          name of a cluster configured in the global OpenShift configuration. The name can also
     *          be blank, which means we will default using the default cluster hostname within a Pod.
     * @param credentialId A literal OAuth token value OR the Jenkins specific identifier of a credential
     *          defined in the Jenkins credentials store.
     */
    public <V> V withCluster( String name=null, String credentialId=null, Closure<V> body ) {

        node {

            // Note that withCluster creates a new Context with null parent. This means that it does not allow
            // operations search outside of its context for more broadly scoped information (i.e.
            // in the DSL: doAs(y){ withCluster(...){ x } }, the operation x will not see the credential y.
            // Therefore, to enforce DSL clarity, forbid withCluster from being wrapped.
            dieIfWithin( ContextId.WITH_CLUSTER, currentContext, ContextId.WITH_PROJECT, ContextId.DO_AS )

            Context context = new Context( null, ContextId.WITH_CLUSTER );

            // Determine if name is a URL or a cluster name. It is treated as a URL if it is *not* found
            // as a cluster configuration name.
            ClusterConfig cc = config.getClusterConfig( name );

            if ( name == null ) {
                // See if a cluster named "default" has been defined.
                cc = config.getClusterConfig( "default" );
            }

            if ( cc == null ) {
                // Presumably, a URL has been specified. If "insecure://..." is a prefix, https is used, but TLS
                // verification is skipped.
                if ( name != null ) {
                    boolean skipTLS = false;
                    if ( name.startsWith( "insecure://" ) ) {
                        skipTLS = true;
                        name = "https://" + name.substring( "insecure://".length() );
                    }
                    context.setServerUrl( name, skipTLS );
                }
            } else {
                context.setServerCertificateAuthorityContent(cc.getServerCertificateAuthority());
                context.setCredentialsId( cc.credentialsId );
                context.setProject(cc.defaultProject);
                context.setServerUrl( cc.getServerUrl(), cc.isSkipTlsVerify() );
            }

            if ( credentialId != null ) {
                context.setCredentialsId( credentialId );
            }

            context.run {
                body()
            }
        }

    }

    public <V> V withProject( String projectName=null, Closure<V> body ) {
        dieIfWithout( ContextId.WITH_PROJECT, currentContext, ContextId.WITH_CLUSTER )
        Context context = new Context( currentContext, ContextId.WITH_PROJECT );
        context.setProject( projectName );
        return context.run {
            body()
        }
    }


    public <V> V doAs( String credentialId=null, Closure<V> body ) {
        dieIfWithout( ContextId.DO_AS, currentContext, ContextId.WITH_CLUSTER )
        Context context = new Context( currentContext, ContextId.DO_AS );
        context.setCredentialsId( credentialId );
        return context.run {
            body()
        }
    }

    public void logLevel(int v) {
        this.@logLevel = v;
    }

    // All lowercase synonym for users familiar with --loglevel on CLI.
    public void loglevel(int v) {
        logLevel(v);
    }

    public void verbose (boolean v=true) {
        logLevel(v?8:0)
    }

    private Map buildCommonArgs( String verb, List verbArgs, String[] userArgsArray, String... overrideArgs ) {

        List optionsBase = [];

        // Override args should come after all user arguments. This allows us to ensure
        // certain behaviors (e.g. if we need to send -o=name).
        if ( overrideArgs != null ) {
            optionsBase.addAll(overrideArgs);
        }

        List verboseOptionsBase = []
        if ( currentContext.isSkipTlsVerify() ) {
            optionsBase.add( "--insecure-skip-tls-verify" );
        } else {
            String caPath = currentContext.getServerCertificateAuthorityPath()
            if ( caPath != null ) {
                verboseOptionsBase.add( "--certificate-authority=" + currentContext.getServerCertificateAuthorityPath() );
            }
        }

        ArrayList<String> userArgsList = (userArgsArray==null)?new ArrayList<String>():Arrays.asList(userArgsArray);

        // These arguments will be mapped, by name, to the constructor parameters of OcAction
        Map args = [
                server:currentContext.getServerUrl(),
                project:currentContext.getProject(),
                verb:verb,
                verbArgs:verbArgs,
                userArgs:userArgsList,
                options:optionsBase,
                verboseOptions: verboseOptionsBase,
                token:currentContext.getToken(),
                logLevel:logLevel
        ]
        return args;
    }

    /**
     * Splits oc verb -o=name output in a list of qualified object names.
     */
    public static ArrayList<String> splitNames( String out ) {
        if ( out == null ) {
            out = "";
        }

        String[] names = out.trim().split("\n");
        List<String> results = new ArrayList<String>();

        for ( int i = 0; i < names.length; i++ ) {
            String name = names[i].trim();
            if ( ! name.isEmpty() ) {
                results.add( name );
            }
        }

        return results;
    }

    @NonCPS
    public HashMap serializableMap( String json ) {
        JsonSlurper js = new JsonSlurper();
        Map m = js.parseText( json );

        /**
         * The Map produced by JsonSlurper is not serializable. If we return it to the user's DSL script and they store it in a global variable,
         * the Jenkins CPS engine will attempt to serialize it before the next AsynchronousStepExecution. This leads to a
         * exceptions like: java.io.NotSerializableException: groovy.json.internal.LazyMap
         * To avoid this, everything we return to the user must be serializable -- transform the JsonSlurper into a serializable map.
         * NonCPS methods may not call non-NonCPS methods and arguments/returns must be serializable.
         * http://stackoverflow.com/questions/37864542/jenkins-pipeline-notserializableexception-groovy-json-internal-lazymap
         */

        HashMap master = new HashMap(m);
        Stack<HashMap> s = new Stack<HashMap>();
        s.push( master );
        while ( s.size() > 0 ) {
            HashMap target = s.pop();
            for (Map.Entry e : target.entrySet()) {
                if ( e.getValue() instanceof Map ) {
                    HashMap he = new HashMap((Map)e.getValue());
                    e.setValue( he );
                    s.push( he );
                }
                if ( e.getValue() instanceof List ) {
                    List l = (List)e.getValue();
                    for ( int i = 0; i < l.size(); i++ ) {
                        Object o = l.get( i );
                        if ( o instanceof Map ) {
                            HashMap he = new HashMap( (Map)o );
                            l.set( i, he );
                            s.push( he );
                        }
                    }
                }
            }
        }
        return master;
    }

    /**
     * @param obj A OpenShift object modeled as a Map
     * @return A Java List containing OpenShift objects. If the parameter models an OpenShift List,
     *          the object will be "unwrapped" and the resulting Java List will contain an entry for each item
     *          in the OpenShift List obj. If the obj is not a list, the return value will be a list with
     *          the obj as its only entry.
     */
    @NonCPS
    public ArrayList<HashMap> unwrapOpenShiftList( HashMap obj ) {
        ArrayList<HashMap> r = new ArrayList<HashMap>();

        if ( obj.kind != "List" ) {
            r.add(obj);
        } else {
            if ( obj.items == null ) {
                obj.items = new ArrayList();
            } else {
                r.addAll(obj.items);
            }
        }
        return r;
    }

    /**
     * @param obj A OpenShift object modeled as a Map or multiple OpenShift objects as List<Map>
     * @return If the parameter is a List, it will be combined into a single OpenShift List model. Otherwise,
     *          the parameter will be returned, unchanged.
     */
    @NonCPS
    public Object toSingleObject(Object obj ) {
        if ( obj instanceof List == false ) {
            return obj;
        }
        // Model an OpenShift List
        HashMap m = new HashMap();
        m.kind = "List";
        m.apiVersion = "v1"
        m.items = obj;
        return m;
    }


    /**
     * The select operation can be executed multiple ways:
     *      selector()   // Selects all
     *      selector( "pod" )  // Selects all of a kind
     *      selector( "dc", "jenkins" )   // selects a particular instance dc/jenkins
     *      selector( "dc/jenkins" )   // selects a particular instance dc/jenkins
     *      selector( "dc", [ alabel: 'avalue' ] ) // Selects using label values
     * When labels are used, the qualifier will be a map. In other cases, expect a String or null.
     */
    public OpenShiftResourceSelector selector(String kind = "all", Object qualifier=null ) {
        return new OpenShiftResourceSelector( "selector", kind, qualifier );
    }

    private OpenShiftResourceSelector objectDefAction(String verb, Object obj, Object[] userArgs) {
        obj = toSingleObject( obj ); // Combine a list of objects into a single OpenShift List model, if necessary.

        if ( obj instanceof Map ) {
            obj = JsonOutput.toJson( obj );
        }

        boolean markup = false;
        String s = obj.toString();
        markup = s.contains("{") || s.contains(":"); // does this look like json or yaml?

        Result r = new Result( verb );

        if ( markup ) {
            FilePath f = currentContext.exec.getWorkspaceFilePath().createTextTempFile( verb, ".markup", s, false );
            try {
                Map stepArgs = buildCommonArgs(verb, [ "-f", f.getRemote() ], userArgs, "-o=name" );
                stepArgs["reference"] = [ "${f.getRemote()}": s ];  // Store the markup content for reference in the result
                r.actions.add((OcAction.OcActionResult)script._OcAction(stepArgs));
            } finally {
                f.delete();
            }
        } else {
            // looks like a subVerb was passed in (e.g. openshift.create( "serviceaccount", "jenkins" ) )
            r.actions.add((OcAction.OcActionResult)script._OcAction( buildCommonArgs(verb, [s ], userArgs, "-o=name" ) ));
        }
        r.failIf( verb + " returned an error" );
        return new OpenShiftResourceSelector( r, OpenShiftDSL.splitNames( r.out ) );

    }

    public OpenShiftResourceSelector create(Object obj,String... args) {
        return objectDefAction( "create", obj, args );
    }

    public OpenShiftResourceSelector replace(Object obj,String... args) {
        return objectDefAction( "replace", obj, args );
    }

    public OpenShiftResourceSelector apply(Object obj,String... args) {
        return objectDefAction( "apply", obj, args );
    }

    public ArrayList<HashMap> process(Object obj,String... args) throws AbortException {
        Result r;

        if ( obj instanceof Map ) {
            if ( obj.kind != "Template" ) {
                throw new AbortException( "Expected Template object, but received: " + obj.toString() );
            }
            // https://github.com/openshift/origin/issues/12277
            Map template = new HashMap( (Map)obj );
            template.metadata.remove('namespace');
            template.metadata.remove('selfLink');
            obj = JsonOutput.toJson( template );
        }

        String target = obj.toString();
        if ( target.contains("{") ) { // is the string JSON?
            String json = obj.toString();
            FilePath f = currentContext.exec.getWorkspaceFilePath().createTextTempFile( "process", ".json", json, false );
            try {
                r = new Result( "process" );
                r.actions.add((OcAction.OcActionResult)script._OcAction( buildCommonArgs("process", ["-f", f.getRemote() ], args, "-o=json" ) ));
                r.failIf( "process returned an error" );
            } finally {
                f.delete();
            }
        } else {
            // Otherwise, the obj parameter is assumed to be a template name
            r = new Result( "process" );
            r.actions.add((OcAction.OcActionResult)script._OcAction( buildCommonArgs("process", [target], args, "-o=json" ) ));
            r.failIf( "process returned an error" );
        }
        // Output should be JSON; unmarshall into a map and transform into a list of objects.
        return unwrapOpenShiftList( serializableMap( r.out ) );
    }

    public Result newProject(String name, String... args ) {
        Result r = new Result( "newProject" );
        r.actions.add( (OcAction.OcActionResult)script._OcAction( buildCommonArgs("new-project", [name], args, "--skip-config-write" ) ) );
        r.failIf( "new-project returned an error" );
        return r;
    }

    public Result raw(String... args ) {
        Result r = new Result( "raw" );
        r.actions.add( (OcAction.OcActionResult)script._OcAction( buildCommonArgs("", null, args ) ) );
        r.failIf( "raw command " + args + " returned an error" );
        return r;
    }


    public Result delete(String... args ) {
        Result r = new Result( "delete" );
        r.actions.add( (OcAction.OcActionResult)script._OcAction( buildCommonArgs("delete", null, args ) ) );
        r.failIf( "delete returned an error" );
        return r;
    }

    public Result set(String... args ) {
        Result r = new Result( "set" );
        r.actions.add( (OcAction.OcActionResult)script._OcAction( buildCommonArgs("set", null, args ) ) );
        r.failIf( "set returned an error" );
        return r;
    }

    private OpenShiftResourceSelector newObjectsAction( String operation, String verb, String[] args) {
        Result r = new Result( operation );
        r.actions.add( (OcAction.OcActionResult)script._OcAction( buildCommonArgs(verb, null, args, "-o=name" ) ) );
        r.failIf( verb + " returned an error" );
        return new OpenShiftResourceSelector( r, OpenShiftDSL.splitNames( r.out ) );
    }


    public OpenShiftResourceSelector newBuild(String... args ) {
        return newObjectsAction( "newBuild", "new-build", args );
    }

    public OpenShiftResourceSelector newApp(String... args ) {
        return newObjectsAction( "newApp", "new-app", args );
    }

    public OpenShiftResourceSelector startBuild(String... args ) {
        return newObjectsAction( "startBuild", "start-build", args );
    }

    private Result simplePassthrough( String verb, String[] args ) {
        Result r = new Result( verb );
        r.actions.add( (OcAction.OcActionResult)script._OcAction( buildCommonArgs(verb, null, args, null ) ) );
        r.failIf( verb + " returned an error" );
        return r;
    }

    public Result exec(String... args ) { return simplePassthrough( "exec", args ); }
    public Result idle(String... args ) { return simplePassthrough( "idle", args ); }
    public Result _import(String... args ) { return simplePassthrough( "import", args ); }
    public Result policy(String... args ) { return simplePassthrough( "policy", args ); }
    public Result run(String... args ) { return simplePassthrough( "run", args ); }
    public Result secrets(String... args ) { return simplePassthrough( "secrets", args ); }
    public Result tag(String... args ) { return simplePassthrough( "tag", args ); }

    public static class Result implements Serializable {

        public final ArrayList<OcAction.OcActionResult> actions = new ArrayList<OcAction.OcActionResult>();
        private final String highLevelOperation;

        public Result( String highLevelOperation ) {
            this.highLevelOperation = highLevelOperation;
        }

        public Result( Result src ) {
            this(src.highLevelOperation);
            this.actions = src.actions;
        }

        @NonCPS
        private void addLine(StringBuilder sb, int indent, String line ) {
            sb.append( "\t"*indent ); // multiply string operation repeats string
            sb.append( line );
            sb.append( "\n" );
        }

        protected failIf(String failMessage ) throws AbortException {
            if ( getStatus() != 0 ) {
                StringBuffer sb = new StringBuffer( failMessage + ";\n" );
                for ( OcAction.OcActionResult actionResult : actions ) {
                    if ( actionResult.isFailed() ) {
                        sb.append( actionResult.toString() );
                        sb.append("\n");
                    }
                }
                throw new AbortException( sb.toString() );
            }
        }

        @NonCPS
        public String getOperation() {
            return this.@highLevelOperation;
        }

        @NonCPS
        public String toString() {
            HashMap m = new HashMap();
            m.put( "operation", highLevelOperation );
            m.put( "status", getStatus() );
            ArrayList actionList = new ArrayList();
            m.put( "actions", actionList );
            for ( OcAction.OcActionResult e : actions ) {
                actionList.add( e.toMap() );
            }
            String json = JsonOutput.prettyPrint(JsonOutput.toJson(m));
            return json;
        }

        @NonCPS
        public String getOut() {
            StringBuilder sb = new StringBuilder();
            for ( OcAction.OcActionResult o : actions ) {
                String s = o.out
                if ( s == null ) {
                    continue;
                }
                sb.append(s);
                if ( !s.endsWith( "\n" ) ) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        }

        @NonCPS
        public String getErr() {
            StringBuilder sb = new StringBuilder();
            for ( OcAction.OcActionResult o : actions ) {
                String s = o.err
                if ( s == null ) {
                    continue;
                }
                sb.append(s);
                if ( !s.endsWith( "\n" ) ) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        }

        @NonCPS
        public int getStatus() {
            int status = 0;
            for ( OcAction.OcActionResult o : actions ) {
                status |= o.status
            }
            return status;
        }

    }

    public class OpenShiftResourceSelector extends Result implements Serializable {

        private String kind;
        private HashMap labels;
        private ArrayList<String> objectList;

        public OpenShiftResourceSelector(String highLevelOperation, String kind, Object qualifier ) {
            super(highLevelOperation);
            kind = kind==null?"all":kind;

            if ( kind.contains("/") ) {
                if ( qualifier != null ) {
                    throw new AbortException( "Unsupported select parameter; only a single argument is permitted if kind/name is specified" );
                }
                String[] s = kind.split( "/" )
                kind = s[0];
                qualifier = s[1];
            }

            if ( qualifier != null ) {
                if ( qualifier instanceof Map ) {
                    this.labels = new HashMap((Map)qualifier);
                } else {
                    // Otherwise, the qualifier is a name that is paired with the kind
                    objectList = Arrays.asList( kind + "/" + qualifier.toString() );
                    kind = null;
                }
            }

            this.kind = kind;
        }

        public OpenShiftResourceSelector(String highLevelOperation, ArrayList<String> objectList ) {
            super(highLevelOperation);
            this.objectList = new ArrayList<String>( objectList );
        }


        public OpenShiftResourceSelector(Result r, ArrayList<String> objectList ) {
            super(r);
            this.objectList = new ArrayList<String>( objectList );
        }

        @NonCPS
        public String toString() {
            return String.format( "selector([kind=%s],[labels=%s],[list=%s])", kind, labels, objectList)
        }

        @NonCPS
        private ArrayList selectionArgs() {
            ArrayList args = new ArrayList();

            if ( objectList != null ) {
                if ( objectList != null ) {
                    objectList.each { e -> args.add( e ); }
                }
            } else {
                args.add( kind );

                if ( labels != null ) {
                    Iterator<Map.Entry> i = labels.entrySet().iterator();
                    while ( i.hasNext() ) {
                        Map.Entry e = i.next();
                        // TODO: handle quotes, newlines, etc?
                        args.add( sprintf("-l %s=%s", e.getKey(),e.getValue()));
                    }
                }
            }

            return args;
        }

        @NonCPS
        private ArrayList<String> flattenLabels(Map labels) {
            ArrayList<String> args = new ArrayList<>();
            if ( labels == null ) {
                return args;
            }
            Iterator<Map.Entry> i = labels.entrySet().iterator();
            while ( i.hasNext() ) {
                Map.Entry e = i.next();
                // TODO: handle quotes, newlines, etc?
                args.add( sprintf("%s=%s", e.getKey(),e.getValue()) );
            }
            return args;
        }

        public Result delete( String... userArgs ) throws AbortException {

            List selectionArgs = selectionArgs();
            if ( kind != null && labels==null ) {
                selectionArgs.add("--all");
            }

            Result r = new Result( "delete" );

            r.actions.add(
                    (OcAction.OcActionResult)script._OcAction( buildCommonArgs("delete", selectionArgs, userArgs) )
            );
            r.failIf( "Error during delete" );
            return r;
        }

        public Result label( Map newLabels, String... userArgs ) throws AbortException {
            List verbArgs = selectionArgs();
            if ( kind != null && labels==null ) {
                verbArgs.add("--all");
            }
            verbArgs.addAll(flattenLabels(newLabels));
            Result r = new Result( "label" );
            r.actions.add(
                    (OcAction.OcActionResult)script._OcAction( buildCommonArgs("label", verbArgs, userArgs) )
            );
            r.failIf( "Error during label" );
            return r;
        }


        public Result describe( String... userArgs ) throws AbortException {
            Result r = new Result( "describe" );
            Map args = buildCommonArgs("describe", selectionArgs(), userArgs);
            args.put( "streamStdOutToConsolePrefix", "describe" );
            r.actions.add(
                    (OcAction.OcActionResult)script._OcAction( args )
            );
            r.failIf( "Error during describe" );
            return r;
        }

        public void watch( Closure<?> body ) {
            script._OcWatch( buildCommonArgs( "get", selectionArgs(), null, "-w", "--watch-only", "-o=name" ) ) {
                body.call( this );
            }
        }

        public void untilEach( int min=1, Closure<?> body ) {
            watch {
                while ( true ) {
                    if ( it.count() < min ) return false;
                    boolean result = true;
                    it.withEach {
                        Object r = body.call( it );
                        if ( r instanceof Boolean == false || ((Boolean)r).booleanValue() == false ) {
                            result = false;
                        }
                    }
                    return result;
                }
            }
        }


        private HashMap _getJson(Map mode=null) throws AbortException {
            boolean exportable = false;
            if ( mode != null ) {
                exportable = (new Boolean( mode.get( "exportable", new Boolean(false) ) )).booleanValue();
            }
            String verb = exportable?"export":"get"
            OcAction.OcActionResult r = (OcAction.OcActionResult)script._OcAction( buildCommonArgs(verb, selectionArgs(), null, "-o=json") );
            r.failIf( "Unable to retrieve object json with " + verb );
            HashMap m = serializableMap( r.out );
            return m;
        }

        public ArrayList<Map> objects(Map mode=null) throws AbortException {
            HashMap m = _getJson(mode);
            return unwrapOpenShiftList(m);
        }

        public int count() throws AbortException {
            return names().size();
        }

        public HashMap object(Map mode=null) throws AbortException {
            HashMap m = _getJson(mode);
            if ( m.kind == "List" ) {
                if ( m.items == null || m.items.size() == 0 ) {
                        throw new AbortException( "Expected single object, but found selection empty" );
                }
                if ( m.items != null && m.items.size() > 1 ) {
                    throw new AbortException( "Expected single object, but found multiple in selection " + m );
                }
                m = (HashMap)((List)m.items).get(0);
            }
            return m;
        }

        public ArrayList<String> names() throws AbortException {
            if ( objectList != null ) {
                return objectList;
            }

            // Otherwise, we need to ask the API server what presently matches
            OcAction.OcActionResult r = (OcAction.OcActionResult)script._OcAction( buildCommonArgs("get", selectionArgs(), null, "-o=name") );
            r.failIf( "Unable to retrieve object names: " + this.toString() );

            return OpenShiftDSL.splitNames( r.out );
        }

        public String name() throws AbortException {
            ArrayList<String> names = names();
            if ( names.size() == 0 ) {
                throw new AbortException( "Expected single name, but found selection empty" );
            }
            if ( names.size() > 1 ) {
                throw new AbortException( "Expected single name, but found multiple in selection: " + names.toString() );
            }
            return names.get(0);
        }

        public Result logs( String... userArgs ) throws AbortException {
            Result r = new Result( "logs" );
            List<String> names = names();
            // oc logs only supports a single object at a time, so get individual names
            for ( String name : names ) {
                Map args = buildCommonArgs("logs", [ name ], userArgs);
                args.put( "streamStdOutToConsolePrefix", "logs:"+name );
                r.actions.add(
                        (OcAction.OcActionResult)script._OcAction( args )
                );
            }
            r.failIf( "Error running logs on at least one item: " + names.toString() );
            return r;
        }

        public OpenShiftResourceSelector startBuild(String... userArgs ) throws AbortException {
            Result r = new Result( "startBuild" );
            List<String> names = names();
            // only supports a single object at a time, so get individual names
            for ( String name : names ) {
                r.actions.add(
                        (OcAction.OcActionResult)script._OcAction( buildCommonArgs("start-build", [name.toString() ], userArgs, "-o=name") )
                );
            }
            r.failIf( "Error running start-build on at least one item: " + names.toString() );
            return new OpenShiftResourceSelector( r, OpenShiftDSL.splitNames( r.out ) );
        }

        private Result onceForEach( String operation, String verb, String[] userArgs) {
            Result r = new Result( operation );
            List<String> names = names();
            for ( String name : names ) {
                r.actions.add(
                        (OcAction.OcActionResult)script._OcAction( buildCommonArgs(verb, [name.toString()], userArgs) )
                );
            }
            r.failIf( "Error running " + verb + " on at least one item: " + names.toString() );
            return r;
        }

        public Result cancelBuild(String... userArgs ) throws AbortException {
            return onceForEach( "cancelBuild", "cancel-build", userArgs );
        }


        public Result deploy(String... userArgs ) throws AbortException {
            return onceForEach( "deploy", "deploy", userArgs );
        }

        public Result scale(String... userArgs ) throws AbortException {
            return onceForEach( "scale", "scale", userArgs );
        }

        public Result autoscale(String... userArgs ) throws AbortException {
            return onceForEach( "autoscale", "autoscale", userArgs );
        }

        public Result expose(String... userArgs ) throws AbortException {
            return onceForEach( "expose", "expose", userArgs );
        }

        public Result volume(String... userArgs ) throws AbortException {
            return onceForEach( "volume", "volume", userArgs );
        }

        public <V> V withEach( Closure<V> body ) {
            List<String> names = names();
            for ( String name : names ) {
                ArrayList<String> nameList = new ArrayList<String>(1);
                nameList.add( name );
                body.call( new OpenShiftResourceSelector( "withEach", nameList ))
            }
        }

        public OpenShiftResourceSelector freeze() throws AbortException {
            return new OpenShiftResourceSelector( "freeze", names() );
        }

        public OpenShiftRolloutManager rollout() throws AbortException {
            return new OpenShiftRolloutManager(this);
        }

        public OpenShiftResourceSelector narrow(String kind ) throws AbortException {
            kind = kind.toLowerCase().trim();

            // Expand abbreviations
            abbreviations.containsKey(kind) && (kind=abbreviations.get(kind));

            ArrayList<String> newList = new ArrayList<String>();
            List<String> names = names();
            for ( String name : names ) {
                String k = name.split("/")[0]
                if ( k.equals( kind ) || (k+"s").equals(kind) ) {
                    newList.add( name );
                }
            }

            return new OpenShiftResourceSelector("narrow", newList);
        }

        public OpenShiftResourceSelector union(OpenShiftResourceSelector sel) throws AbortException {
            HashSet<String> set = new HashSet<String>( this.names() ); // removes duplicates
            set.addAll( sel.names() );
            ArrayList<String> names = new ArrayList<String>( set );
            return new OpenShiftResourceSelector("union",names);
        }


        public OpenShiftResourceSelector related(String kind) throws AbortException {
            kind = kind.toLowerCase().trim();

            // Expand abbreviations
            abbreviations.containsKey(kind) && (kind=abbreviations.get(kind));

            HashMap<String,String> labels = new HashMap<String,String>();
            ArrayList<String> newList = new ArrayList<String>();

            String[] split = name().split("/");
            String k = split[0];
            String unqualifiedName = split[1];
            switch ( k ) {
                case "template" :
                    labels.put( "template", unqualifiedName );
                    break;
                case "deploymentconfig" :
                    labels.put( "deploymentconfig", unqualifiedName );
                    break;
                case "buildconfig" :
                    labels.put( "openshift.io/build-config.name", unqualifiedName );
                    break;
                default:
                    throw new AbortException( "Unknown how to find resources related to kind: " + k );
            }

            return new OpenShiftResourceSelector("related", kind, labels);
        }

    }

    public class OpenShiftRolloutManager implements Serializable {

        private final OpenShiftResourceSelector selector;

        public OpenShiftRolloutManager( OpenShiftResourceSelector selector ) {
            this.@selector = selector;
        }

        private Result runSubVerb(String subVerb, String[] args, boolean streamToStdout=false ) throws AbortException {
            Result r = new Result( "rollout:" + subVerb );
            selector.withEach {
                String dcName = it.name();
                List verbArgs = [ subVerb, dcName ];
                Map stepArgs = buildCommonArgs("rollout", verbArgs, args, null );
                stepArgs.streamStdOutToConsolePrefix = "rollout:" + subVerb + ":" + dcName;
                r.actions.add( (OcAction.OcActionResult)script._OcAction( stepArgs ) );
            }
            r.failIf( r.highLevelOperation + " returned an error" );
            return r;
        }

        public Result history( String...args ) throws AbortException { return runSubVerb("history", args, true); }
        public Result latest( String...args ) throws AbortException { return runSubVerb("latest", args); }
        public Result pause( String...args ) throws AbortException { return runSubVerb("pause", args); }
        public Result resume( String...args ) throws AbortException { return runSubVerb("resume", args); }
        public Result status( String...args ) throws AbortException { return runSubVerb("status", args, true); }
        public Result undo( String...args ) throws AbortException { return runSubVerb("undo", args); }

    }


        private <V> V node(Closure<V> body) {
        if (script.env.NODE_NAME != null) {
            // Already inside a node block.
            body()
        } else {
            script.node {
                body()
            }
        }
    }

}