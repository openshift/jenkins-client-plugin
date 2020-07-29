/*pipeline {
    agent none
    stages {
        stage('test init') {
    	    steps {
    	        script {
	                openshift.setLockName('openshift-dls-test')
    	        }
    	    }
        }
        stage('Run tests') {
            parallel {
                stage('Test run 1') {
                    steps {
                        script {
                            actualTest()
                        }
                    }
                }
      
                stage('Test run 2') {
                    steps {
                        script {
                            actualTest()
                        }
                    }
                }
            }
        }
    }
}

void actualTest() {*/
    /**
     * This script does nothing in particular,
     * but is meant to show actual usage of most of the API.
     */
    
    try {
        timeout(time: 20, unit: 'MINUTES') {
            // Select the default cluster
            openshift.withCluster() {
                // Test openshift.patch and selector.patch
                /* openshift.withProject() {
                  def currentProject = openshift.project()
                  def templateSelector = openshift.selector( "template", "nodejs-example")
                  if (!templateSelector.exists() ) {
                      openshift.create("https://raw.githubusercontent.com/openshift/nodejs-ex/master/openshift/templates/nodejs.json")
                  } else {
                    openshift.selector( 'svc', [ app:'nodejs-example' ] ).delete()
                    openshift.selector( 'routes', [ app:'nodejs-example' ] ).delete()
                    openshift.selector( 'dc', [ app:'nodejs-example' ] ).delete()
                    openshift.selector( 'is', [ app:'nodejs-example' ] ).delete()
                    openshift.selector( 'bc', [ app:'nodejs-example' ] ).delete()
                  } 
                  openshift.newApp("--template=${currentProject}/nodejs-example")
                  openshift.patch("dc/nodejs-example", '\'{"spec":{"strategy":{"type":"Recreate"}}}\'')
                  def mySelector = openshift.selector("bc/nodejs-example")
                  mySelector.patch('\'{"spec":{"source":{"git":{"ref": "development"}}}}\'')
                } */
                // Select the default project
                openshift.withProject() {
    
                    // Output the url of the currently selected cluster
                    echo "Using project ${openshift.project()} in cluster with url ${openshift.cluster()}"
    
                    // Test selector.annotate
                    def railsTemplate = openshift.create("https://raw.githubusercontent.com/openshift/rails-ex/master/openshift/templates/rails-postgresql.json")
                    railsTemplate.annotate([key1:"value1", key2:"value2"])
                    railsTemplate.delete()
    
                    def saSelector1 = openshift.selector( "serviceaccount" )
                    saSelector1.describe()
    
                    def templateSelector = openshift.selector( "template", "mongodb-ephemeral")
    
                    def templateExists = templateSelector.exists()
    
                    def templateGeneratedSelector = openshift.selector(["dc/mongodb", "service/mongodb", "secret/mongodb"])
    
                    def objectsGeneratedFromTemplate = templateGeneratedSelector.exists()
                    
                    // create single object in array
                    def bc = [[
                        "kind":"BuildConfig",
                        "apiVersion":"v1",
                        "metadata":[
                            "name":"test",
                            "labels":[
                                "name":"test"
                            ]
                        ],
                        "spec":[
                            "triggers":[],
                            "source":[
                                "type":"Binary"
                            ],
                            "strategy":[
                                "type":"Source",
                                "sourceStrategy":[
                                    "from":[
                                        "kind":"DockerImage",
                                        "name":"centos/ruby-25-centos7"
                                    ]
                                ]
                            ],
                            "output":[
                                "to":[
                                    "kind":"ImageStreamTag",
                                    "name":"test:latest"
                                ]
                            ]
                        ]
                      ]
                    ]    
                    def objs = openshift.create( bc )
                    objs.describe()
                    openshift.delete("bc", "test")
                    // switch to delete below when v1.0.10 is available in the image
                    //openshift.delete(bc)
                    openshift.create("configmap", "foo")
                    openshift.create("configmap", "bar")
                    openshift.delete("configmap/foo", "configmap/bar")
                    openshift.create("configmap", "foo")
                    openshift.delete("configmap/foo")

                    def template
                    if (!templateExists) {
                        template = openshift.create('https://raw.githubusercontent.com/openshift/origin/master/examples/db-templates/mongodb-ephemeral-template.json').object()
                    } else {
                        template = templateSelector.object()
                    }
    
                    // Explore the Groovy object which models the OpenShift template as a Map
                    echo "Template contains ${template.parameters.size()} parameters"
    
                    // For fun, modify the template easily while modeled in Groovy
                    template.labels["mylabel"] = "myvalue"

                    // verify we can handle unquoted param values with spaces
                    //def muser = "All Users"
                    //openshift.process( template, '-p', "MONGODB_USER=${muser}")
                    //def exist2 = openshift.selector("template", "grape-spring-boot").exists()
                    //if (!exist2) {
                    //    openshift.create("https://raw.githubusercontent.com/openshift/jenkins-client-plugin/master/examples/issue184-template.yml")
                    //}
                    //def exist3 = openshift.selector("template", "postgresql-ephemeral").exists()
                    //if (!exist3) {
                    //   openshift.create('https://raw.githubusercontent.com/openshift/origin/master/examples/db-templates/postgresql-ephemeral-template.json')
                    //}
                    //openshift.process("postgresql-ephemeral", "-p=MEMORY_LIMIT=120 -p=NAMESPACE=80 -p=DATABASE_SERVICE_NAME=\"-Xmx768m -Dmy.sys.param=aete\" -p=POSTGRESQL_USER=verify -p=POSTGRESQL_PASSWORD=aete -p=POSTGRESQL_DATABASE=400 -p=POSTGRESQL_VERSION=grape-regtest-tools-aete")
                    //openshift.process("grape-spring-boot", "-p=LIVENESS_INITIAL_DELAY_SECONDS=120 -p=READYNESS_INITIAL_DELAY_SECONDS=80 -p=JVMARGS=\"-Xmx768m -Dmy.sys.param=aete\"-p=APPNAME=verify -p=DEPLOYMENTTAG=aete -p=ROLLING_TIMEOUT_SECONDS=400 -p=NAMESPACE=grape-regtest-tools-aete")
                    //openshift.process("grape-spring-boot", "-p LIVENESS_INITIAL_DELAY_SECONDS=120 -p READYNESS_INITIAL_DELAY_SECONDS=80 -p JVMARGS=\"-Xmx768m -Dmy.sys.param=aete\"-p APPNAME=verify -p DEPLOYMENTTAG=aete -p ROLLING_TIMEOUT_SECONDS=400 -p NAMESPACE=grape-regtest-tools-aete")
                    //openshift.process("grape-spring-boot", "-p=LIVENESS_INITIAL_DELAY_SECONDS=120", "-p=READYNESS_INITIAL_DELAY_SECONDS=80", "-p=JVMARGS=\"-Xmx768m -Dmy.sys.param=aete\"", "-p=APPNAME=verify", "-p=DEPLOYMENTTAG=aete", "-p=ROLLING_TIMEOUT_SECONDS=400", "-p=NAMESPACE=grape-regtest-tools-aete")
    
                    // Process the modeled template. We could also pass JSON/YAML, a template name, or a url instead.
                    // note: -p option for oc process not in the oc version that we currently ship with openshift jenkins images
                    def objectModels = openshift.process( template )//, "-p", "MEMORY_LIMIT=600Mi")
    
                    // objectModels is a list of objects the template defined, modeled as Groovy objects
                    echo "The template references ${objectModels.size()} objects"
    
                    // For fun, modify the objects that have been defined by processing the template
                    for ( o in objectModels ) {
                        o.metadata.labels[ "anotherlabel" ] = "anothervalue"
                    }
    
                    def objects
                    def verb
                    if (!objectsGeneratedFromTemplate) {
                        verb = "Created"
                        // Serialize the objects and pass them to the create API.
                        // We could also pass JSON/YAML directly; openshift.create(readFile('some.json'))
                        objects = openshift.create(objectModels)
                    } else {
                        verb = "Found"
                        objects = templateGeneratedSelector
                    }
    
                    // Create returns a selector which will always select the objects created
                    objects.withEach {
                        // Each loop binds the variable 'it' to a selector which selects a single object
                        echo "${verb} ${it.name()} from template with labels ${it.object().metadata.labels}"
                    }
    
                    // Filter created objects and create a selector which selects only the new DeploymentConfigs
                    def dcs = objects.narrow("dc")
                    echo "Database will run in deployment config: ${dcs.name()}"
                    // Find a least one pod related to the DeploymentConfig and wait it satisfies a condition
                    dcs.related('pods').untilEach(1) {
                        // untilEach only terminates when each selected item causes the body to return true
                        if (it.object().status.phase != 'Pending') {
                        // some example debug of the pod in question
                            shortname = it.object().metadata.name
                            echo openshift.rsh("${shortname}", "ps ax").out
                            return true;
                        }
                        return false;
                    }
    
                    // Print out all pods created by the DC
                    echo "Template created pods: ${dcs.related('pods').names()}"
    
                    // Show how we can use labels to select as well
                    echo "Finding dc using labels instead: ${openshift.selector('dc',[mylabel:'myvalue']).names()}"
    
                    echo "DeploymentConfig description"
                    dcs.describe()
                    echo "DeploymentConfig history"
                    dcs.rollout().history()

                    //openshift.verifyService('mongodb')
    
                    def rubySelector = openshift.selector("bc", "ruby")
                    def builds
                    try {
                        rubySelector.object()
                        builds = rubySelector.related( "builds" )
                    } catch (Throwable t) {
                        // The selector returned from newBuild will select all objects created by the operation
                        nb = openshift.newBuild( "https://github.com/openshift/ruby-hello-world", "--name=ruby" )
    
                        // Print out information about the objects created by newBuild
                        echo "newBuild created: ${nb.count()} objects : ${nb.names()}"
    
                        // Filter non-BuildConfig objects and create selector which will find builds related to the BuildConfig
                        builds = nb.narrow("bc").related( "builds" )
    
                    }
                    
                    //make sure we handle empty selectors correctly
                    def nopods = openshift.selector("pod", [ app: "asdf" ])
                    nopods.withEach {
                      echo "should not see this echo"
                    }
             
                    // Raw watch which only terminates when the closure body returns true
                    builds.watch {
                        // 'it' is bound to the builds selector.
                        // Continue to watch until at least one build is detected
                        if ( it.count() == 0 ) {
                            return false
                        }
                        // Print out the build's name and terminate the watch
                        echo "Detected new builds created by buildconfig: ${it.names()}"
                        return true
                    }
    
                    echo "Waiting for builds to complete..."
    
                    // Like a watch, but only terminate when at least one selected object meets condition
                    builds.untilEach {
                        return it.object().status.phase == "Complete"
                    }
    
                    // Print a list of the builds which have been created
                    echo "Build logs for ${builds.names()}:"
    
                    // Find the bc again, and ask for its logs
                    def result = rubySelector.logs()
    
                    // Each high-level operation exposes stout/stderr/status of oc actions that composed
                    echo "Result of logs operation:"
                    echo "  status: ${result.status}"
                    echo "  stderr: ${result.err}"
                    echo "  number of actions to fulfill: ${result.actions.size()}"
                    echo "  first action executed: ${result.actions[0].cmd}"
    
                    // The following steps below are geared toward testing of bugs or features that have been introduced
                    // into the openshift client plugin since its initial release
    
                    // exercise oc run path, including verification of proper handling of groovy cps
                    // var binding (converting List to array)
		    // using the quay origin-jenkins:4.3+ as it deploys without error on 4.x
                    def runargs1 = []
                    runargs1 << "jenkins-second-deployment"
                    runargs1 << "--image=quay.io/openshift/origin-jenkins:4.3"
                    runargs1 << "--dry-run"
                    runargs1 << "-o yaml"
                    openshift.run(runargs1)
    
                    // FYI - pipeline cps groovy compile does not allow String[] runargs2 =  {"jenkins-second-deployment", "--image=docker.io/openshift/jenkins-2-centos7:latest", "--dry-run"}
                    String[] runargs2 = new String[4]
                    runargs2[0] = "jenkins-second-deployment"
                    runargs2[1] = "--image=quay.io/openshift/origin-jenkins:4.3"
                    runargs2[2] = "--dry-run"
                    runargs2[3] = "-o yaml"
                    openshift.run(runargs2)
    
        	    // add this rollout -w test when v0.9.6 is available in our centos image so
                    // the overnight tests pass
                    //def dc2Selector = openshift.selector("dc", "jenkins-second-deployment")
                    //if (dc2Selector.exists()) {
                    //    openshift.delete("dc", "jenkins-second-deployment")
                    //}
	
                    //openshift.run("jenkins-second-deployment", "--image=quay.io/openshift/origin-jenkins:4.3")
                    //dc2Selector.rollout().status("-w")
                    //dc2Selector.rollout().latest()

                    // Empty static / selectors are powerful tools to check the state of the system.
                    // Intentionally create one using a narrow and exercise it.
                    emptySelector = openshift.selector("pods").narrow("bc")
                    openshift.failUnless(!emptySelector.exists()) // Empty selections never exist
                    openshift.failUnless(emptySelector.count() == 0)
                    openshift.failUnless(emptySelector.names().size() == 0)
                    emptySelector.delete() // Should have no impact
                    emptySelector.label(["x":"y"]) // Should have no impact
    
                    // sanity check for latest and cancel
                    def dc3Selector = openshift.selector("dc", "mongodb")
                    dc3Selector.rollout().latest()
                    sleep 3
                    dc3Selector.rollout().cancel()
    
                    // perform a retry on a failed or cancelled deployment
                    //dc3Selector.rollout().retry()
    
                    // validate some watch/selector error handling
                    try {
                        timeout(time: 10, unit: 'SECONDS') {
                            builds.untilEach {
                                  sleep(20)
                            }
                        }
                        error( "exception did not escape the watch as expected" )
                    } catch ( e ) {
                        // test successful
                    }
                    try {
                        builds.watch {
                            error( "this should be thrown" )
                        }
                        error( "exception did not escape the watch as expected" )
                    } catch ( e ) {
                        // test successful
                    }
    
                }
            }
        }
    } catch (err) {
        echo "in catch block"
        echo "Caught: ${err}"
        currentBuild.result = 'FAILURE'
        throw err
    }
        
//}

