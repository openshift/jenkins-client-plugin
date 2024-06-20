package e2e

import (
	"context"
	"testing"
	"time"

	v1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/wait"
	kclientset "k8s.io/client-go/kubernetes"
)

type Tester struct {
	client           kclientset.Interface
	namespace        string
	podName          string
	errorPassThrough bool
	t                *testing.T
}

func NewTester(client kclientset.Interface, ns string, t *testing.T) *Tester {
	return &Tester{client: client, namespace: ns, t: t}
}

// createExecPod creates a simple centos:7 pod in a sleep loop used as a
// vessel for kubectl exec commands.
// Returns the name of the created pod.
func (ut *Tester) CreateExecPod(ctx context.Context, name string, cmd string) {
	client := ut.client.CoreV1()
	_, err := client.Pods(ut.namespace).Get(ctx, name, metav1.GetOptions{})
	if err == nil {
		ut.t.Log("curl job logs pod already exists")
		return
	}
	ut.t.Logf("Creating new curl pod")
	immediate := int64(0)
	user := int64(65532)
	truVal := true
	falVal := false
	execPod := &v1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name:      name,
			Namespace: ut.namespace,
		},
		Spec: v1.PodSpec{
			ServiceAccountName: "jenkins",
			Containers: []v1.Container{
				{
					Command:         []string{"/bin/bash", "-c", cmd},
					Name:            "hostexec",
					Image:           "quay.io/redhat-developer/test-build-simples2i:latest",
					ImagePullPolicy: v1.PullIfNotPresent,
					SecurityContext: &v1.SecurityContext{
						AllowPrivilegeEscalation: &falVal,
						Capabilities: &v1.Capabilities{
							Drop: []v1.Capability{"ALL"},
						},
						RunAsNonRoot: &truVal,
						RunAsUser:    &user,
						SeccompProfile: &v1.SeccompProfile{
							Type: v1.SeccompProfileTypeRuntimeDefault,
						},
					},
				},
			},
			HostNetwork:                   false,
			TerminationGracePeriodSeconds: &immediate,
		},
	}
	created, err := client.Pods(ut.namespace).Create(ctx, execPod, metav1.CreateOptions{})
	if err != nil {
		ut.t.Fatalf("%#v", err)
	}
	err = wait.PollUntilContextTimeout(ctx, 1*time.Second, 5*time.Minute, true, func(ctx context.Context) (bool, error) {
		retrievedPod, err := client.Pods(execPod.Namespace).Get(ctx, created.Name, metav1.GetOptions{})
		ut.t.Logf("get of curl pod %s got err %#v", created.Name, err)
		if err != nil {
			return false, nil
		}
		for _, status := range retrievedPod.Status.ContainerStatuses {
			if status.State.Terminated != nil {
				ut.t.Logf("pod is terminated with exit code %d", status.State.Terminated.ExitCode)
				return true, nil
			}
			if status.State.Waiting != nil {
				ut.t.Logf("waiting on pod: %s", status.State.Waiting.Reason)
			}
			if status.State.Running != nil {
				ut.t.Logf("pod is running since %s", status.State.Running.StartedAt)
			}
		}
		ut.t.Logf("done with container state, still cehcking")
		return false, nil
	})
	if err != nil {
		ut.t.Fatalf("%#v", err)
	}
}
