
all: test-e2e


test-e2e:
	oc version
	go version
	./hack/tag-ci-image.sh
	KUBERNETES_CONFIG=${KUBECONFIG} go test -timeout 30m -v ./test/e2e/...
