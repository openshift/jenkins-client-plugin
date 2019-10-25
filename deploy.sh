#!/bin/sh

# Deploys locally the plugin in a pod which has label name=jenkins
# You must be correctly logged in an OpenShift or Kubernetes cluster (KUBECONFIG set or oc login)
pod_name=$( oc get pods -l name=jenkins --output=name | cut -f2 -d/)
plugin_path=/var/lib/jenkins/plugins
plugin_name=$( xmllint --xpath "/*[local-name() = 'project']/*[local-name() = 'artifactId']/text()" pom.xml )
plugin_dst_extension=jpi
plugin_src_extension=hpi
plugin_full_path=$plugin_path/$plugin_name.$plugin_dst_extension

local_plugin_path=target/$plugin_name.$plugin_src_extension
remote_plugin_path=$pod_name:$plugin_full_path
remote_plugin_directory=$plugin_path/$plugin_name

echo "Copying $local_plugin_path into $remote_plugin_path"
oc cp $local_plugin_path  $remote_plugin_path
echo "Unzipping $plugin_full_path into $remote_plugin_directory" 
oc exec $pod_name -- unzip -o $plugin_full_path -d $remote_plugin_directory
echo "Restarting container"
oc exec $pod_name -- kill 1
