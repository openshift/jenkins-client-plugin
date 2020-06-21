
all: test-e2e


test-e2e:
	KUBERNETES_CONFIG=${KUBECONFIG} go test -parallel 1 -timeout 30m -v ./test/e2e/...


