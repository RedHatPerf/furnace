# Furnace

Furnace is a utility tool for producing [Flame graphs](http://www.brendangregg.com/flamegraphs.html) in Openshift environment.

## Quickstart

Install the operator by adding this catalogsource:

```yaml
apiVersion: operators.coreos.com/v1alpha1
kind: CatalogSource
metadata:
  name: furnace-manifests
  namespace: openshift-marketplace
spec:
  sourceType: grpc
  image: quay.io/rvansa/furnace-index:latest
```

Add a subscription (possibly through webconsole):

```yaml
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: furnace-operator
  namespace: openshift-operators
spec:
  channel: alpha
  name: furnace-operator
  source: furnace-manifests
  sourceNamespace: openshift-marketplace
```

Create a new namespace (or use existing one) and add the Furnace resource:

```sh
oc new project furnace
```
```yaml
apiVersion: furnace.hyperfoil.io/v1alpha1
kind: Furnace
metadata:
  name: furnace
spec: {}
```

You can open `https://furnace-furnace.apps.your.domain` and check that the webapp is running, but there won't be any pods to profile. Suppose that you want to profile pod `my-pod` in namespace `my-ns`, so first enable profiling in that namespace:

```
oc label namespace my-ns furnace=enabled
```

and then adjust the `deployment`/`deploymentconfig` or another resource (e.g. if you're spinning it up through another operator) so that the pod contains either label or annotation `furnace: enabled` as well.

It's insufficient to label the pod after it is created; that would not trigger the mutating webhook and a furnace sidecar would not be injected.

Since the sidecar requires elevated privileges, check out which service account your pod is using (most likely it's `default`) and then give it `anyuid` and `privileged` SCCs:

```sh
oc get po my-pod -o jsonpath='{.spec.serviceAccountName}' # prints out default
oc adm policy add-scc-to-user anyuid -z default
oc adm policy add-scc-to-user privileged -z default
```

Note: you should be aware that these privileges mean effectively root access on the host. Use with caution and profile only containers that you absolutely trust (and you need to trust Furnace as well).

When you roll out new pod you can see that it's starting with 2 containers. Get back to the webapp, select the namespace and pod in the menus and push the button to start recording. When you stop it, after a few moments your flamegraph should appear.

You might wonder why you can't simply pick the node in the webapp and let it to the labeling and set up privileges. That is ceratinly possible but the webapp would have to operate with very high privileges itself. One solution for that is impersonation (using your own token). Maybe in the future.

## How does this work

The operator deploys a simple webserver that has three roles:

### 1. Mutating webhook

The webhook watches all namespaces labeled with `furnace: enabled`. If a new pod is created its definition is again checked for label or annotation `furnace: enabled`. If both these conditions hold, the pod gets injected a sidecar container with Furnace.

### 2. Proxy

All sidecars periodically register itself on a proxy; therefore we need only single route to the service and this can proxy requests to sidecars.

### 3. UI

The webserver exposes a simplistic UI to create, view and download the recording.

## In the sidecar

Openshift does not allow to mount a sibling container as a filesystem. Therefore in order to get the symbols correctly the sidecar runs a podman, pulling the image of the profiled container and running it with a no-op entrypoint. The mount point of this container is written into `/out/mountpoint` and `perf` can use that to resolve symbols. As the image may be not public the sidecar mounts all pull secrets available to the pod and writes them into `/root/.docker/config.json`.

In order to support BCC tools (inject eBPF programs) we also mount `/lib/modules`, `/sys/kernel/debug` and `/usr/src/kernels` from host to the container. To have some of these present on the hosts the `kernel-devel` machineconfig extension is required. Since ATM we don't implement this feature [(we can't resolve symbols from another location)](https://github.com/iovisor/bcc/issues/3197) the operator does not install this extension.

## Building

Build the operator, bundle and index using

```sh
cd furnace-operator
make docker-build docker-push IMG=quay.io/rvansa/furnace-operator
podman push quay.io/rvansa/furnace-operator
make bundle IMG=quay.io/rvansa/furnace-operator
make bundle-build BUNDLE_IMG=quay.io/rvansa/furnace-operator-bundle
podman push quay.io/rvansa/furnace-operator-bundle
opm index add --bundles quay.io/rvansa/furnace-operator-bundle:latest --tag quay.io/rvansa/furnace-index:latest
podman push quay.io/rvansa/furnace-index
```

Build the webhook/proxy/UI with

```sh
cd furnace-app
./mvnw package -Dquarkus.container-image.build=true -Dquarkus.container-image.push=true
```

Prepare the sidecar image with:
```sh
cd furnace-app
podman build -t quay.io/rvansa/furnace -f src/main/docker/Dockerfile.furnace . && podman push quay.io/rvansa/furnace
```

Note: since the sidecar contains the application as well it must be built before the sidecar.
