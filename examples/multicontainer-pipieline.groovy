pipeline {
    agent {
      kubernetes {
        cloud 'openshift'
        label 'mypod'
        defaultContainer 'jnlp'
        yaml """
apiVersion: v1
kind: Pod
metadata:
  labels:
    some-label: some-label-value
spec:
  serviceAccount: jenkins
  containers:
  - name: maven
    image: docker.io/openshift/jenkins-agent-maven-35-centos7:v3.11
    command:
    - cat
    tty: true
"""
      }
    }
      stages {
          stage('build') {
              steps {
                  container('maven') {
                      script {
                          openshift.withCluster() {
                              openshift.withProject() {
                                  def dcSelector = openshift.selector("dc", "jenkins")
                                  dcSelector.describe()
                              }
                          }
                      }
                  }
              }
          }
      }
  }
  