
# OpenShift Jenkins Pipeline (DSL) Plugin - Experimental

<!-- Install doctoc with `npm install -g doctoc`  then `doctoc README.md --github` -->
<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->


- [Overview](#overview)
- [Reader Prerequisites](#reader-prerequisites)
- [Examples](#examples)
  - [Hello, World](#hello-world)
  - [Centralizing Cluster Configuration](#centralizing-cluster-configuration)
  - [Sticking with the defaults](#sticking-with-the-defaults)
  - [Introduction to Selectors](#introduction-to-selectors)
  - [Actions speak louder than words](#actions-speak-louder-than-words)
  - [Peer inside of OpenShift objects](#peer-inside-of-openshift-objects)
  - [Watching and waiting? Of course!](#watching-and-waiting-of-course)
  - [Deleting objects. Easy.](#deleting-objects-easy)
  - [Creating objects. Easier than you were expecting... hopefully.](#creating-objects-easier-than-you-were-expecting-hopefully)
  - [Need to update an object without replacing it?](#need-to-update-an-object-without-replacing-it)
  - [Can't live without OpenShift templates? No problem.](#cant-live-without-openshift-templates-no-problem)
  - [Want to promote / migrate objects between environments?](#want-to-promote--migrate-objects-between-environments)
  - [Error handling](#error-handling)
  - [Troubleshooting](#troubleshooting)
  - [Who are you, really?](#who-are-you-really)
  - [I need more.](#i-need-more)
- [Configuring an OpenShift Cluster](#configuring-an-openshift-cluster)
- [Setting up Credentials](#setting-up-credentials)
- [Setting up Nodes](#setting-up-nodes)
- [You call this documentation?!](#you-call-this-documentation)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

## Overview
The [OpenShift](https://www.openshift.com) [Pipeline](https://jenkins.io/solutions/pipeline/) 
DSL Plugin is presently an experimental Jenkins plugin which aims to provide a readable, concise, comprehensive, and fluent 
[Jenkins Pipeline](https://jenkins.io/doc/book/pipeline/) syntax for rich interactions with an OpenShift API Server. The 
plugin leverages an OpenShift command line tool (oc) which must be available on the nodes executing the script
(options for getting the binary on your nodes can be found [here](#setting-up-nodes)).

If you are interested in a non-experimental Jenkins plugin, find it
[here](https://github.com/openshift/jenkins-plugin).  

## Reader Prerequisites
* Familiarity with OpenShift [command line interface](https://docs.openshift.org/latest/cli_reference/basic_cli_operations.html)
is highly encouraged before exploring the plugin's features. The DSL leverages the [oc](https://docs.openshift.org/latest/cli_reference/index.html) 
binary and, in many cases, passes method arguments directly on to the command line. This document cannot, therefore,
provide a complete description of all possible OpenShift interactions -- the user may need to reference
the CLI documentation to find the pass-through arguments a given interaction requires.
* A fundamental level of understanding of the Jenkins [Pipeline](https://jenkins.io/solutions/pipeline/) architecture and 
[basic pipeline steps](https://jenkins.io/doc/pipeline/steps/workflow-basic-steps/) may be required to appreciate
the remainder of this document. Readers may also find it useful to understand basic [Groovy syntax](http://groovy-lang.org/syntax.html),
since pipeline scripts are written using Groovy (Note: Jenkins sandboxes and [interferes](https://issues.jenkins-ci.org/browse/JENKINS-26481) 
with the use of some Groovy facilities). 

## Examples

As the DSL is designed to be intuitive for experienced OpenShift users, the following high level examples 
may serve to build that intuition before delving into the detailed documentation.

### Hello, World
Let's start with a "Hello world" style example. 

```groovy
/** Use of hostnames and OAuth token values in the DSL is heavily discouraged for maintenance and **/
/** security reasons. The global Jenkins configuration and credential store should be used instead. **/
/** Subsequent examples will demonstrate how to do this. **/
openshift.withCluster( 'https://10.13.137.207:8443', 'CO8wPaLV2M2yC_jrm00hCmaz5Jgw...' ) {
    openshift.withProject( 'myproject' ) {
        echo "Hello from project: ${openshift.project()}"
    }
}
```

### Centralizing Cluster Configuration
Now let's simplify the first example by moving host, port, token and project information out of the script and into the
[Jenkins cluster configuration](#configuring-an-openshift-cluster). A single logical name (e.g. "mycluster")
can now be used to reference these values. This means that if the cluster information changes in the future, your 
scripts won't have to!
 
```groovy
/** The logical name references a Jenkins cluster configuration which implies **/
/** API Server URL, default credentials, and a default project to use within the closure body. **/
openshift.withCluster( 'mycluster' ) {
    echo "Hello from mycluster's default project: ${openshift.project()}"
    
    // But we can easily change project contexts
    openshift.withProject( 'another-project' ) {
        echo "Hello from a non-default project: ${openshift.project()}"
    }
    
    // And even scope operations to other clusters within the same script
    openshift.withCluster( 'myothercluster' ) {
        echo "Hello from myothercluster's default project: ${openshift.project()}"
    }
}
```

### Sticking with the defaults
We can make the previous example even simpler! If you have defined a cluster configuration named 
"default" or if the Jenkins instance is running within an OpenShift pod, you
don't need to specify any cluster information. 

```groovy
openshift.withCluster() { // Use "default" cluster or fallback to OpenShift cluster detection
    echo "Hello from the project running Jenkins: ${openshift.project()}"
}
```

### Introduction to Selectors
Now a quick introduction to Selectors which allow you to perform operations 
on a group of API Server objects.

```groovy
openshift.withCluster( 'mycluster' ) {
    /** Selectors are a core concept in the DSL. They allow the user to invoke operations **/
    /** on group of objects which satisfy a given criteria. **/
    
    // Create a Selector capable of selecting all service accounts in mycluster's default project
    def saSelector = openshift.selector( 'serviceaccount' )
    
    // Prints `oc describe serviceaccount` to Jenkins console
    saSelector.describe() 
    
    // Selectors also allow you to easily iterate through all objects they currently select.
    saSelector.withEach { // The closure body will be executed once for each selected object.
        // The 'it' variable will be bound to a Selector which selects a single
        // object which is the focus of the iteration.
        echo "Service account: ${it.name()} is defined in ${openshift.project()}"
    }
    
    // Prints a list of current service accounts to the console
    echo "There are ${saSelector.count()} service accounts in project ${openshift.project()}"
    echo "They are named: ${saSelector.names()}"
    
    // Selectors can also be defined using qualified names
    openshift.selector( 'deploymentconfig/frontend' ).describe()
    
    // Or Kind + Label information
    openshift.selector( 'dc', [ tier: 'frontend' ] ).describe()
    
}
```

### Actions speak louder than words
Describing things is fine, but let's actually make something happen! Here, notice
that new Selectors are regularly returned by DSL operations.

```groovy
openshift.withCluster( 'mycluster' ) {
    // Run `oc new-app https://github.com/openshift/ruby-hello-world` . It 
    // returns a Selector which will select the objects it created for you.
    def created = openshift.newApp( 'https://github.com/openshift/ruby-hello-world' )
    
    // This Selector exposes the same operations you have already seen.
    // (And many more that you haven't!).
    echo "new-app created ${created.count()} objects named: ${created.names()}"
    created.describe()
    
    // We can create a Selector from the larger set which only selects 
    // the build config which new-app just created.
    def bc = created.narrow('bc')
    
    // Let's output the build logs to the Jenkins console. bc.logs()
    // would run `oc logs bc/ruby-hello-world`, but that might only 
    // output a partial log if the build is in progress. Instead, we will
    // pass '-f' to `oc logs` to follow the build until it terminates.
    // Arguments to logs get passed directly on to the oc command line.
    def result = bc.logs('-f')
    
    // Many operations, like logs(), return a Result object (even a Selector
    // is a subclass of Result). A Result object contains detailed information about
    // what actions, if any, took place to accomplish an operation.
    echo "The logs operation require ${result.actions.size()} oc interactions"
    
    // You can even see exactly what oc command was executed.
    echo "Logs executed: ${result.actions[0].cmd}"
    
    // And even obtain the standard output and standard error of the command.
    def logsString = result.actions[0].out
    def logsErr = result.actions[0].err
}
```

### Peer inside of OpenShift objects 

```groovy
openshift.withCluster( 'mycluster' ) {
    def dcs = openshift.newApp( 'https://github.com/openshift/ruby-hello-world' ).narrow('dc')
    
    // dcs is a Selector which selects the deployment config created by new-app. How do
    // we get more information about this DC? Turn it into a Groovy object using object().
    // If there was a chance here that more than one DC was created, we should use objects()
    // which would return a List of Groovy objects; however, in this example, there 
    // should only be one.
    def dc = dcs.object()
    
    // dc is not a Selector -- It is a Groovy Map which models the content of the DC
    // new-app created at the time object() was called. Changes to the model are not
    // reflected back to the API server, but the DC's content is at our fingertips.
    echo "new-app created a ${dc.kind} with name ${dc.metadata.name}"
    echo "The object has labels: ${dc.metadata.labels}"
    
}
```


### Watching and waiting? Of course!
Patience is a virtue.

```groovy
openshift.withCluster( 'mycluster' ) {
    def bc = openshift.newApp( 'https://github.com/openshift/ruby-hello-world' ).narrow('bc')
    
    // The build config will create a new build object automatically, but how do
    // we find it? The 'related(kind)' operation can create an appropriate Selector for us.
    def builds = bc.related('builds')

    // There are no guarantees in life, so let's interrupt these operations if they
    // take more than 10 minutes and fail this script.
    timeout(10) {
    
        // We can use watch to execute a closure body each objects selected by a Selector
        // change. The watch will only terminate when the body returns true.
        builds.watch {
            // Within the body, the variable 'it' is bound to the watched Selector (i.e. builds)
            echo "So far, ${bc.name()} has created builds: ${it.names()}"
            
            // End the watch only once a build object has been created.
            return it.count() > 0   
        }
        
        // But we can actually want to wait for the build to complete.
        builds.watch {
            if ( it.count() == 0 ) return false
            
            // A robust script should not assume that only one build has been created, so
            // we will need to iterate through all builds.
            def allDone = true
            it.withEach {
                // 'it' is now bound to a Selector selecting a single object for this iteration.
                // Let's model it in Groovy to check its status.
                def buildModel = it.object() 
                if ( it.object().status.phase != "Complete" ) {
                    allDone = false
                }
            }
            
            return allDone;
        }
        
        
        // Uggh. That was actually a significant amount of code. Let's use untilEach(){} 
        // instead. It acts like watch, but only executes the closure body once
        // a minimum number of objects meet the Selector's criteria only terminates 
        // once the body returns true for all selected objects.
        builds.untilEach(1) { // We want a minimum of 1 build
        
            // Unlike watch(), untilEach binds 'it' to a Selector for a single object.
            // Thus, untilEach will only terminate when all selected objects satisfy this 
            // the condition established in the closure body (or until the timeout(10) 
            // interrupts the operation).
        
            return it.object().status.phase == "Complete"
        }
    }
```    
    
     
### Deleting objects. Easy.

```groovy
openshift.withCluster( 'mycluster' ) {

    // Delete all deployment configs with a particular label.
    openshift.selector( 'dc', [ environment:'qe' ] ).delete()

}
```

### Creating objects. Easier than you were expecting... hopefully.

```groovy
openshift.withCluster( 'mycluster' ) {

        // You can use sub-verbs of create for some simple objects
        openshift.create( 'serviceaccount', 'my-service-account' )
        
        // But you want to craft your own, don't you? First,
        // model it with Groovy Maps, Lists, and primitives.
        def secret = [
            "kind": "Secret",
            "metadata": [
                "name": "mysecret",
                "labels": [
                    'findme':'please'
                ]
            ],
            "stringData": [ 
                "username": "myname",
                "password": "mypassword"
            ]
        ]        

        // create will marshal the model into JSON and send it to the API server.
        // We will add some passthrough arguments (--save-config and --validate)
        // just for fun.
        def objs = openshift.create( secret, '--save-config', '--validate' )
        
        // create(...) returns a Selector will select the resulting object(s).
        objs.describe()
        
        // But, you say, I've already modeled my object in JSON/YAML! It is in 
        // an SCM or accessible with via HTTP, or..., or ... 
        // Don't worry. Just get it to the current Jenkins workspace any way
        // you want (e.g. using a Jenkins plugin for your SCM). Then read the 
        // file into a String using normal Jenkins steps.
        def fromJSON = openshift.create( readFile( 'myobjects.json' ) )
        
        // You will get a Selector for the objects created, as always.
        echo "Created objects from JSON file: ${fromJSON.names()}"
        
}
```

### Need to update an object without replacing it?

```groovy
openshift.withCluster( 'mycluster' ) {
    def p = openshift.selector( 'pods/mypod' ).object()
    p.metadata.labels['newlabel']='newvalue' // Adjust the model
    openshift.apply(p) // Patch the object on the server
}   
```

### Can't live without OpenShift templates? No problem.

```groovy
openshift.withCluster( 'mycluster' ) {
    
    // One straightforward way is to pass string arguments directly to `oc process`.
    // This includes any parameter values you want to specify.
    def models = openshift.process( "openshift//mongodb-ephemeral", "-p", "MEMORY_LIMIT=600Mi" )
    
    // A list of Groovy object models that were defined in the template will be returned.
    echo "Creating this template will instantiate ${models.size()} objects"

    // For fun, modify the objects that have been defined by processing the template
    for ( o in models ) {
        o.metadata.labels[ "mylabel" ] = "myvalue"
    }
    
    // You can pass this list of object models directly to the create API
    def created = openshift.create( models )
    echo "The template instantiated: ${models.names()}"

    // Want more control? You could model the template itself!
    def template = openshift.withProject( 'openshift' ) {
        // Find the named template and unmarshal into a Groovy object
        openshift.selector('template','mysql-ephemeral').object()
    }

    // Explore the template model
    echo "Template contains ${template.parameters.size()} parameters"

    // For fun, modify the template easily while modeled in Groovy
    template.labels["mylabel"] = "myvalue"

    // This model can be specified as the template to process
    openshift.create( openshift.process( template, "-p", "MEMORY_LIMIT=600Mi" ) )
    
}
```

### Want to promote / migrate objects between environments?

```groovy
openshift.withCluster( 'devcluster' ) {
    
    def maps = openshift.selector( 'dc', [ microservice: 'maps' ] )
    
    // Model the source objects using the 'exportable' flag to strip cluster
    // specific information from the objects (i.e. like 'oc export').
    def objs = maps.objects( exportable:true )
    
    // Modify the models as you see fit.
    def timestamp = "${System.currentTimeMillis()}"
    for ( obj in objs ) {
        obj.metadata.labels[ "promoted-on" ] = timestamp
    }
    
    openshift.withCluster( 'qecluster' ) {
    
        // Might want Jenkins to ask someone before we do this ;-)
        mail (
            to: 'devops@acme.com',
            subject: "Maps microservice (${env.BUILD_NUMBER}) is awaiting promotion",
             body: "Please go to ${env.BUILD_URL}.");
        input "Ready to update QE cluster with maps microservice?"
        
        // Note that the selector is relative to its closure body and 
        // operates on the qecluster now.
        maps.delete( '--ignore-not-present' )
        
        openshift.create( objs )
        
        // Let's wait until at least one pod is Running
        maps.related( 'pods' ).untilEach {
            return it.object().status.phase == 'Running'
        }
    }

}
```

### Error handling
Error handling is fairly standard for Jenkins DSL scripts. try/catch blocks
can be used prevent recoverable errors from causing a build to fail.

```groovy
openshift.withCluster( 'mycluster' ) {
    
    try {
        openshift.doAs( 'some-invalid-token-value' ) {
            openshift.newProject( 'my-new-project' )
            // ...
        }
    } catch ( e ) {
        // The exception is a hudson.AbortException with details
        // about the failure.
        "Error encountered: ${e}"
    }
    
}
```

The error printed out to the Jenkins console would look something like (and yes,
the token will be masked as shown):
```
Error encountered: hudson.AbortException: new-project returned an error; sub-step failed: 
{reference={}, err=error: You must be logged in to the server (the server has asked for the client to provide credentials), 
verb=new-project, cmd=oc my-new-project --skip-config-write --insecure-skip-tls-verify 
--server=https://192.168.1.109:8443 --namespace=myproject --token=XXXXX , out=, status=1}
```

### Troubleshooting
Want to see the details of your OpenShift API Server interactions?
 
```groovy
openshift.withCluster( 'mycluster' ) {
    
    openshift.verbose() 
    // Get details printed to the Jenkins console and pass high --log-level to all oc commands
    openshift.newProject( 'my-new-project' )
    openshift.verbose(false) // Turn it back 

    // If you want verbose output, but want a specific log-level
    openshift.logLevel(3)
    openshift.newProject( 'my-new-project-2' )
    ....
}
```


### Who are you, really?
Getting advanced? You might need more than just default credentials associated
with your cluster. You can leverage any OpenShift Token credential type in the Jenkins
credential store by passing doAs the credential's identifier. If you think 
security is a luxury you can live without (it's not), you can also pass doAs 
a raw token value.

```groovy
openshift.withCluster( 'mycluster' ) {
    openshift.doAs( 'my-normal-credential-id' ) {
        ...
    }

    openshift.doAs( 'my-privileged-credential-id' ) {
        ...
    }

    // Raw token value. Not recommended.
    openshift.doAs( 'CO8wPaLV2M2yC_jrm00hCmaz5Jgw...' ) {
        ...
    }
}
```


### I need more.
If the available DSL operations are not sufficient, you can always pass a
raw command directly to the oc binary. If you do not specify a server,
token, or project, normal closure context rules will apply.
```groovy
openshift.withCluster( 'mycluster' ) {
    def result = openshift.raw( 'status', '-v' )
    echo "Cluster status: ${result.out}"
}
```

But honestly, wouldn't you rather contribute and add the operation you need? ;-)


## Configuring an OpenShift Cluster

Are you running your Jenkins instance within an OpenShift cluster? Does it
only interact with resources within that cluster? You might not need to do anything here!
Leaving out the cluster name when calling openshift.withCluster will cause
the plugin to try:
1. To access a Jenkins cluster configuration named "default" and, if one does not exist..
2. To assume it is running within an OpenShift Pod with a service account. In this scenario, 
the following cluster information will be used:
  * API Server URL: "https://${env.KUBERNETES_SERVICE_HOST}:${env.KUBERNETES_SERVICE_PORT_HTTPS}"
  * File containing Server Certificate Authority: /run/secrets/kubernetes.io/serviceaccount/ca.crt
  * File containing Pod's project name: /run/secrets/kubernetes.io/serviceaccount/namespace
  * File containing OAuth Token: /run/secrets/kubernetes.io/serviceaccount/token

```groovy
openshift.withCluster() {  // find "default" cluster configuration and fallback to OpenShift cluster detection
    // ... operations relative to the default cluster ...
}
```

If you do need to configure clusters, it is a simple matter. As an authorized 
Jenkins user, navigate to Manage Jenkins -> Configure System -> and find the OpenShift Plugin section.
 
Add a new cluster and you should see a form like the following. 
![cluster-config](src/readme/images/cluster-config.png)

The cluster "name" (e.g. "mycluster") is the only thing you need to remember when writing scripts.
If the cluster configuration has a default credential or project, they will be used automatically
when operations are performed relative to that cluster (unless they are explicitly overridden).
```groovy
openshift.withCluster( 'mycluster' ) {
    // ... operations relative to this cluster ...
}
```

## Setting up Credentials
To define a new credential for the DSL in the Jenkins credential store, navigate to 
Credentials -> System -> Global credentials -> Add Credentials (you can the domain based
on your particular security requirements). 

![token-config](src/readme/images/token-credential-config.png)

This token can then be selected as the default token for a given Jenkins configuration
cluster OR used tactically in the DSL with openshift.doAs( 'my-privileged-credential-id' ) {...} .

## Setting up Nodes
Each Jenkins node (master/agents) must have a copy of the OpenShift command line tool (oc) installed and in the
Jenkins PATH environment variable. If your Jenkins nodes are running OpenShift images,
stop reading here: they are already installed!

If your nodes are running outside of OpenShift, you can install the tool on each node
yourself, or use Jenkins' Tool installer to do it for you. To do this, as authorized 
Jenkins user, navigate to Manage Jenkins -> Global Tool Configuration -> and find 
the "OpenShift Client Tools" section.
  
Here you can define a version of the client tools, where to find them, and if they
should be automatically instead when a node requires them.

In the following example, a logical name "oc1.3.2" is associated with a particular
build of the [client tools available on github](https://github.com/openshift/origin/releases).

![tool-config-by-url](src/readme/images/tool-config-by-url.png)

Using this tool is then a simple matter of executing the OpenShift operations with
the PATH adjusted to give it preference. If configured as above, the client tools
will automatically be installed once on nodes that use the Pipeline 'tool' step.

```groovy
node('agent1') {
    def ocDir = tool "oc1.3.2"
    withEnv(["PATH+OC=${ocDir}"]) {
        openshift.withCluster( 'mycluster' ) {
            echo "${openshift.raw( "version" ).out}"
            echo "In project: ${openshift.project()}"
        }
    }
}
```

Please refer to Jenkins documentation on Global Tool Configuration which allows, for example,
Linux and Windows nodes to acquire different builds of a tool.

 
## You call this documentation?!
Not exactly. This is a brief overview of some of the capabilities of the plugin. The details
of the API are embedded within the plugin's online documentation within a running Jenkins instance. 
To find it:
1. Create a new Pipeline Item 
2. Click "Pipeline Syntax" below the DSL text area
3. On the left navigation menu, click "Global Variables Reference"

A preview is provided below, but please see the Global Variable Reference in a running
instance for the latest API information.

<!-- Created using Firefox Nimbus screenshot plugin. Choose page fragment -->
![jenkins-online-help](src/readme/images/jenkins-online-help.png)