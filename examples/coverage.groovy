/**
 * This script does nothing in particular (in fact, it deletes its work),
 * but is meant to show actual usage of most of the API.
 */

openshift.withCluster('mycluster') {

    def saSelector1 = openshift.selector( "serviceaccount" )
    saSelector1.describe()
    def saSelector2 = openshift.selector( "sa/builder" )
    def saSelector3 = openshift.selector( "sa", "builder" )
    def saSelector4 = openshift.selector( "dc", [ "template" : "mytemplate" ] )

    def projectName = "temp-" + System.currentTimeMillis()
    echo "Using temporary project: ${projectName}"
    openshift.newProject( projectName , '--display-name="self test"' )

    // Within the 'openshift' project
    def template = openshift.withProject( 'openshift' ) {
        // Find the named template and unmarshal into a Groovy object
        openshift.selector('template','mongodb-ephemeral').object()
    }

    openshift.withProject( projectName ) {

        // Explore the Groovy object which models the OpenShift template as a Map
        echo "Template contains ${template.parameters.size()} parameters"

        // For fun, modify the template easily while modeled in Groovy
        template.labels["mylabel"] = "myvalue"

        // Process the modeled template. We could also pass JSON/YAML or a template name instead.
        def objectModels = openshift.process( template, "-p", "MEMORY_LIMIT=600Mi")

        // objectModels is a list of objects the template defined, modeled as Groovy objects
        echo "The template will create ${objectModels.size()} objects"

        // For fun, modify the objects that have been defined by processing the template
        for ( o in objectModels ) {
            o.metadata.labels[ "anotherlabel" ] = "anothervalue"
        }

        // Serialize the objects and pass them to the create API.
        // We could also pass JSON/YAML directly; openshift.create(readFile('some.json'))
        def created = openshift.create(objectModels)

        // Create returns a selector which will always select the objects created
        created.withEach {
            // Each loop binds the variable 'it' to a selector which selects a single object
            echo "Created ${it.name()} from template with labels ${it.object().metadata.labels}"
        }

        // Filter created objects and create a selector which selects only the new DeploymentConfigs
        def dcs = created.narrow("dc")
        echo "Database will run in deployment config: ${dcs.name()}"
        timeout(5) {
            // Find a least one pod related to the DeploymentConfig and wait it satisfies a condition
            dcs.related('pods').untilEach(1) {
                // untilEach only terminates when each selected item causes the body to return true
                return it.object().status.phase != 'Pending'
            }
        }

        // Print out all pods created by the DC
        echo "Template created pods: ${dcs.related('pods').names()}"

        // Show how we can use labels to select as well
        echo "Finding dc using labels instead: ${openshift.selector('dc',[mylabel:'myvalue']).names()}"

        echo "DeploymentConfig description"
        dcs.describe()
        echo "DeploymentConfig history"
        dcs.rollout().history()

        // The selector returned from newBuild will select all objects created by the operation
        def nb = openshift.newBuild( "https://github.com/openshift/ruby-hello-world", "--name=ruby" )

        // Print out information about the objects created by newBuild
        echo "newBuild created: ${nb.count()} objects : ${nb.names()}"

        // Filter non-BuildConfig objects and create selector which will find builds related to the BuildConfig
        def builds = nb.narrow("bc").related( "builds" )

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
        def result = nb.narrow("bc").logs()

        // Each high-level operation exposes stout/stderr/status of oc actions that composed
        echo "Result of logs operation:"
        echo "  status: ${result.status}"
        echo "  stderr: ${result.err}"
        echo "  number of actions to fulfill: ${result.actions.size()}"
        echo "  first action executed: ${result.actions[0].cmd}"

    }

    openshift.delete( "project", projectName )
}