import jenkins.model.Jenkins

import com.openshift.jenkins.plugins.OpenShift
import com.openshift.jenkins.plugins.ClusterConfig

OpenShift.DescriptorImpl openshiftDSL = (OpenShift.DescriptorImpl)Jenkins.getInstance().getDescriptor("com.openshift.jenkins.plugins.OpenShift")

ClusterConfig cluster1 = new ClusterConfig("cluster1")
cluster1.setServerUrl("https://cluster:8443")
cluster1.setServerCertificateAuthority(new File("path/to/file").getText("UTF-8"))

openshiftDSL.addClusterConfig(cluster1)
openshiftDSL.save()
