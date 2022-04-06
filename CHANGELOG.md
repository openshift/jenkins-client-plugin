
## 1.0.32
- Upgrade findbugs for java 8 compatibility
- Pipeline CPS method mismatch: - Turn contextContains method into @NonCPS , - fix bug with dieIfNotWithin which does the opposite check
- Updated README
- Updated OpenshiftGlobalVariable
- Update verifyService() to verify Headless Services as well
- add devtools to owners
- add note about hard coding of -o=name and it getting in the way of some oc combinations

## 1.0.31
- prune inadvertent glog/klog to stdout
- finally migrate fix oc path logging to verbose only
- Enhance documentation
- travis/prow no longer copacetic
- remove space param test again since we have 3.11 jenkins e2e working again and haven't decided about bumping the version of client plug-in in 3.11

## 1.0.30
- deal with possible spaces in job names when accessing workspace based tmp dirs
- add warning around load and jenkins restart
- create Dockerfile to enable prow based PR jobs against v4.x clusters

## 1.0.29

## 1.0.28
- create groovy shim tmp files in workspace dir to facilitate file sharing in k8s multi container scenarios
- Porting the current process launching approach to ClientCommandRunner
- Fix issues of FindOc
- Add adambkaplan as approver

## 1.0.27
- BUG 1679937: update freestyle argument help for type/id form

## 1.0.26
- scope oc search to only directories on path
- Update README.md

## 1.0.25
- add path to oc as needed by finding oc on target computer and matching to path;
- add link to jenkins jira on declarative/globalvars

## 1.0.24
- mimic pre 1.0.17 style processing for watch/action, but ditch use of files (given validations of both master/agent flow); retry around intermittent json format exceptions for oc partial output; no longer have to fix path to oc
- updating readme
- updating readme with openshift sync instructions

## 1.0.22

## 1.0.21

## 1.0.20

## 1.0.19
- revert latest template param test; something is up with the jenkins image version used with the overnight tests (#187)
- Update README.md

## 1.0.18

## 1.0.17

## 1.0.16

## 1.0.15

## 1.0.14

## 1.0.13
- Update README.md
- Fixing a couple of typos in the README.md (#157)
- fix eclipse parsing error

## 1.0.12
- fix multi namespace on new -o=name output (#150)

## 1.0.11
- adjust to the metadata.kind on returned objects having values like deploymentconfig.apps.openshift.io (#148)

## 1.0.10

## 1.0.9
- add retry to RolloutManager (#129)

## 1.0.8

## 1.0.7

## 1.0.6
- Update README.md

## 1.0.5

## 1.0.4

## 1.0.3

## 1.0.2
- need getters for raw step; readme fixes

## 1.0.1
- Adding sample for recently added options

## 1.0.0

## 0.9.7

## 0.9.6

## 0.9.5
- commented out test until new version: narrow closure and enabling the use of empty static selectors

## 0.9.4

## 0.9.3
- comment out exists test until we can update jenkins image

## 0.9.2

## 0.9.1

## 0.9.0
- bump sample total timeout

## 0.8
- version tweak for release publish
- Fixes for running within OpenShift pod
- Search and replace fixes
- Adding findbugs exceptions
- Another round of jenkins ci findbugs
- Changing to non-snapshot version
- Changing pom.xml url to Jenkins wiki
- Removing Java8 dependencies
- Fix for Add credentials button not working
- pom.xml updates for Jenkins publish process
- Migrating from https://github.com/jupierce/openshift-jenkins-pipeline-dsl
- Initial commit
