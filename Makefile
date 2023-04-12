all: test-e2e
.PHONY: all

test-e2e:
	./hack/tag-ci-image.sh
	KUBERNETES_CONFIG=${KUBECONFIG} go test -timeout 30m -v ./test/e2e/...
.PHONY: test-e2e

verify:
	mvn verify
.PHONY: verify
