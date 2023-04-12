#!/bin/sh
#
# Copyright © 2016 Red Hat, Inc. (https://www.redhat.com)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#


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
