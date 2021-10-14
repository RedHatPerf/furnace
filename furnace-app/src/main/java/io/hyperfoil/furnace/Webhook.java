package io.hyperfoil.furnace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@Path("/webhook")
public class Webhook {
    private static final Logger log = Logger.getLogger(Webhook.class);
    private static final String FURNACE_IMAGE = "quay.io/rvansa/furnace-app:latest";
    private static final String SERVICE_NAME = System.getenv("SERVICE_NAME");
    private static final String POD_NAME = System.getenv("POD_NAME");
    private static final String POD_NAMESPACE = System.getenv("POD_NAMESPACE");
    private static final boolean ADD_KERNEL_SRC = Util.getBooleanEnv("ADD_KERNEL_SRC", true);
    private static final String CONTAINER_STORAGE_NFS = System.getenv("CONTAINER_STORAGE_NFS");
    private String keystore;
    private final Map<String, String> images = new HashMap<>();

    @PostConstruct
    public void init() {
        try {
            int rc = Runtime.getRuntime().exec("keytool -importcert -file /var/certs/tls.crt -keystore /tmp/keystore.jks -storepass changeit -trustcacerts -noprompt").waitFor();
            if (rc != 0) {
                log.error("Failed to generate keystore: " + rc);
                return;
            }
        } catch (InterruptedException | IOException e) {
            log.error("Failed to generate keystore.", e);
            return;
        }
        try {
            keystore = Base64.getEncoder().encodeToString(Files.readAllBytes(Paths.get("/tmp/keystore.jks")));
        } catch (IOException e) {
            log.error("Failed to read keystore", e);
        }

        java.nio.file.Path imagesPath = Paths.get("/var/images");
        if (Files.isRegularFile(imagesPath)) {
            try {
                for (String line : Files.readAllLines(imagesPath)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int firstSpace = line.indexOf(' ');
                    if (firstSpace < 0) {
                        log.error("Invalid image record " + line);
                    }
                    String original = line.substring(0, firstSpace);
                    String replace = line.substring(firstSpace + 1).trim();
                    if (replace.isEmpty()) {
                        log.error("Invalid image replacement " + line);
                    }
                    images.put(original, replace);
                }
            } catch (IOException e) {
                log.error("Failed to read images alternatives");
            }
        }
    }

    @POST
    @Path("mutate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public JsonObject mutate(JsonObject request) {
        return mutate(request, false);
    }

    @POST
    @Path("always-mutate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public JsonObject alwaysMutate(JsonObject request) {
        return mutate(request, true);
    }

    private JsonObject mutate(JsonObject request, boolean alwaysInject) {
        JsonObject pod = request.getJsonObject("request").getJsonObject("object");
        log.infof("Inspecting pod %s/%s (%s) with these containers: %s",
              pod.getJsonObject("metadata").getString("namespace"),
              pod.getJsonObject("metadata").getString("name"),
              pod.getJsonObject("metadata"),
              pod.getJsonObject("spec").getJsonArray("containers").stream().map(c -> ((JsonObject) c).getString("name")).collect(Collectors.toList()));
        JsonArray patch = createPatch(pod, alwaysInject);
        JsonObject review = new JsonObject();
        JsonObject response = new JsonObject();
        response.put("apiVersion", "admission.k8s.io/v1beta1");
        response.put("kind", "AdmissionReview");
        response.put("response", review);
        review.put("uid", request.getJsonObject("request").getValue("uid"));
        review.put("allowed", true);
        review.put("patchType", "JSONPatch");
        review.put("patch", Base64.getEncoder().encodeToString(patch.encode().getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private JsonArray createPatch(JsonObject pod, boolean alwaysInject) {
        JsonArray patch = new JsonArray();
        addToPatch(patch, "/metadata/annotations", new JsonObject().put("furnace.inspected", "true"));
        JsonObject metadata = pod.getJsonObject("metadata");
        JsonObject annotations = metadata.getJsonObject("annotations");
        JsonObject labels = metadata.getJsonObject("labels");
        JsonObject spec = pod.getJsonObject("spec");
        boolean inject = alwaysInject;
        if (annotations != null && isEnabled(annotations.getValue("furnace"), false)) {
            inject = true;
        } else if (labels != null && isEnabled(labels.getValue("furnace"), false)) {
            inject = true;
        }
        for (Object c : spec.getJsonArray("containers")) {
            if (c instanceof JsonObject && "furnace".equals(((JsonObject) c).getString("name"))) {
                // already has the sidecar - e.g. it's a debug container
                inject = false;
            }
        }
        if (!inject) {
            return patch;
        }
        boolean pull = true;
        if (annotations != null && !isEnabled(annotations.getValue("furnace.pull"), true)) {
            pull = false;
        } else if (labels != null && !isEnabled(labels.getValue("furnace.pull"), true)) {
            pull = false;
        }
        JsonArray volumes = new JsonArray();
        JsonArray imagePullSecrets = spec.getJsonArray("imagePullSecrets");
        JsonObject sidecar = new JsonObject();
        sidecar.put("name", "furnace");
        sidecar.put("image", FURNACE_IMAGE);
        sidecar.put("imagePullPolicy", "Always");
        sidecar.put("ports", new JsonArray().add(new JsonObject().put("containerPort", 12380)));
        sidecar.put("securityContext", new JsonObject().put("privileged", true).put("runAsUser", 0));
        sidecar.put("startupProbe", new JsonObject()
              .put("failureThreshold", 1000)
              .put("httpGet", new JsonObject().put("port", 12380).put("path", "/controller/ready")));
        JsonArray volumeMounts = new JsonArray();
        volumes.add(new JsonObject().put("name", "output").put("emptyDir", new JsonObject()));
        addMount(volumeMounts, "output", "/out", false);
        addHostPath(volumes, "kernel-modules", "/lib/modules");
        addMount(volumeMounts, "kernel-modules", "/lib/modules", true);
        addHostPath(volumes, "kernel-debug", "/sys/kernel/debug");
        addMount(volumeMounts, "kernel-debug", "/sys/kernel/debug", true);
        volumes.add(new JsonObject().put("name", "scripts").put("configMap",
              new JsonObject().put("name", "furnace-scripts").put("optional", true).put("defaultMode", 0777)
        ));
        addMount(volumeMounts, "scripts", "/scripts", true);
        JsonObject containersStorage = new JsonObject().put("name", "containers-storage");
        if (CONTAINER_STORAGE_NFS == null) {
            containersStorage.put("emptyDir", new JsonObject());
        } else {
            int lastColon = CONTAINER_STORAGE_NFS.lastIndexOf(':');
            String server, path;
            if (lastColon < 0) {
                server = CONTAINER_STORAGE_NFS;
                path = "/";
            } else {
                server = CONTAINER_STORAGE_NFS.substring(0, lastColon);
                path = CONTAINER_STORAGE_NFS.substring(lastColon + 1);
            }
            containersStorage.put("nfs", new JsonObject().put("server", server).put("path", path));
        }
        volumes.add(containersStorage);
        addMount(volumeMounts, "containers-storage", "/containers/storage", false);
        if (ADD_KERNEL_SRC) {
            addHostPath(volumes, "kernel-src", "/usr/src/kernels");
            addMount(volumeMounts, "kernel-src", "/usr/src/kernels", true);
        }
        JsonArray sources = new JsonArray();
        if (imagePullSecrets != null && !imagePullSecrets.isEmpty()) {
            for (Object ps : imagePullSecrets) {
                String name = ((JsonObject) ps).getString("name");
                sources.add(new JsonObject().put("secret", new JsonObject()
                      .put("name", name)
                      .put("optional", true)
                      .put("items", new JsonArray()
                            .add(new JsonObject().put("key", ".dockerconfigjson").put("path", name + ".dockerconfigjson"))
                            .add(new JsonObject().put("key", ".dockercfg").put("path", name + ".dockercfg"))
                        )));
            }
            volumes.add(new JsonObject().put("name", "pull-secrets").put("projected",
                  new JsonObject().put("defaultMode", 256).put("sources", sources)));
            addMount(volumeMounts, "pull-secrets", "/etc/pull-secrets/", true);
        }
        addHostPath(volumes, "var-lib-kubelet", "/var/lib/kubelet");
        volumeMounts.add(new JsonObject()
              .put("name", "var-lib-kubelet").put("subPath", "config.json")
              // we cannot mount into /etc/pull-secrets
              .put("mountPath", "/etc/kubelet.config.json")
              .put("readOnly", true));

        String image = spec.getJsonArray("containers").getJsonObject(0).getString("image");
        if (images.containsKey(image)) {
            // replace images to avoid DockerHub limits
            image = images.get(image);
        }
        JsonArray env = new JsonArray();
        addEnv(env, "FURNACE_SIDECAR", "true");
        addEnv(env, "FURNACE_MAIN_IMAGE", image);
        addEnvFromField(env, "POD_NAME", "metadata.name");
        addEnvFromField(env, "POD_NAMESPACE", "metadata.namespace");
        addEnvFromField(env, "POD_IP", "status.podIP");
        addEnv(env, "QUARKUS_HTTP_PORT", "12380");
        addEnv(env, "PROXY_CLIENT_MP_REST_URL", "https://" + SERVICE_NAME + "." + POD_NAMESPACE + ".svc:443");
        addEnv(env, "PROXY_CLIENT_MP_REST_TRUSTSTORE", "file:/root/keystore.jks");
        addEnv(env, "PROXY_CLIENT_MP_REST_TRUSTSTOREPASSWORD", "changeit");
        addEnv(env, "KEYSTORE", keystore);
        addEnv(env, "PULL_IMAGE", String.valueOf(pull));
        addEnvFromConfigMap(env, "AUTOSTART", "autostart");
        addEnvFromConfigMap(env, "AUTOSTART_DELAY", "autostartDelay");
        addEnvFromConfigMap(env, "AUTOSTOP", "autostop");
        addEnvFromConfigMap(env, "AUTORESTART", "autorestart");
        addEnvFromConfigMap(env, "PROCESS_PATTERN", "processPattern");

        sidecar.put("volumeMounts", volumeMounts);
        sidecar.put("env", env);
        addToPatch(patch, "/spec/containers/-", sidecar);
        addToPatch(patch, "/spec/shareProcessNamespace", true);
        addToPatch(patch, "/spec/securityContext/runAsNonRoot", false);
        if (spec.containsKey("volumes")) {
            for (Object volume : volumes) {
                addToPatch(patch, "/spec/volumes/-", volume);
            }
        } else {
            addToPatch(patch, "/spec/volumes", volumes);
        }
        return patch;
    }

    private void addEnvFromField(JsonArray env, String name, String path) {
        env.add(new JsonObject().put("name", name).put("valueFrom",
              new JsonObject().put("fieldRef", new JsonObject().put("fieldPath", path))));
    }

    private void addEnvFromConfigMap(JsonArray env, String name, String key) {
        env.add(new JsonObject().put("name", name).put("valueFrom",
              new JsonObject().put("configMapKeyRef",
                    new JsonObject().put("name", "furnace-config").put("key", key).put("optional", true))));
    }

    private void addMount(JsonArray volumeMounts, String name, String path, boolean readOnly) {
        volumeMounts.add(new JsonObject().put("name", name).put("mountPath", path).put("readOnly", readOnly));
    }

    private void addHostPath(JsonArray volumes, String name, String path) {
        volumes.add(new JsonObject().put("name", name).put("hostPath",
              new JsonObject().put("path", path).put("type", "Directory")
        ));
    }

    private void addEnv(JsonArray env, String name, String value) {
        env.add(new JsonObject().put("name", name).put("value", value));
    }

    private void addToPatch(JsonArray patch, String path, Object value) {
        patch.add(new JsonObject().put("op", "add").put("path", path).put("value", value));
    }

    private boolean isEnabled(Object annotation, boolean defaultValue) {
        if (annotation instanceof String) {
            String str = (String) annotation;
            return "enable".equalsIgnoreCase(str) ||
                  "enabled".equalsIgnoreCase(str) ||
                  "true".equalsIgnoreCase(str) ||
                  "yes".equalsIgnoreCase(str);
        } else if (annotation instanceof Boolean) {
            return (Boolean) annotation;
        } else {
            return defaultValue;
        }
    }
}