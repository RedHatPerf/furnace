package io.hyperfoil.furnace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

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
    private static final String FURNACE_IMAGE = "quay.io/rvansa/furnace:latest";
    private static final String SERVICE_NAME = System.getenv("SERVICE_NAME");
    private static final String POD_NAME = System.getenv("POD_NAME");
    private static final String POD_NAMESPACE = System.getenv("POD_NAMESPACE");
    private String keystore;

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
    }

    @POST
    @Path("mutate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public JsonObject mutate(JsonObject request) {
        JsonObject pod = request.getJsonObject("request").getJsonObject("object");
        JsonArray patch = createPatch(pod);
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

    private JsonArray createPatch(JsonObject pod) {
        JsonArray patch = new JsonArray();
        addToPatch(patch, "/metadata/annotations", new JsonObject().put("furnace.inspected", "true"));
        JsonObject metadata = pod.getJsonObject("metadata");
        JsonObject annotations = metadata.getJsonObject("annotations");
        JsonObject labels = metadata.getJsonObject("labels");
        if (annotations == null || !isEnabled(annotations.getValue("furnace")) && (labels == null || !isEnabled(labels.getValue("furnace")))) {
            return patch;
        }
        JsonArray volumes = new JsonArray();
        JsonArray imagePullSecrets = pod.getJsonObject("spec").getJsonArray("imagePullSecrets");
        JsonObject sidecar = new JsonObject();
        sidecar.put("name", "furnace");
        sidecar.put("image", FURNACE_IMAGE);
        sidecar.put("ports", new JsonArray().add(new JsonObject().put("containerPort", 12380)));
        sidecar.put("securityContext", new JsonObject().put("privileged", true));
        sidecar.put("startupProbe", new JsonObject()
              .put("failureThreshold", 1000)
              .put("httpGet", new JsonObject().put("port", 12380).put("path", "/controller/ready")));
        JsonArray volumeMounts = new JsonArray();
        volumes.add(new JsonObject().put("name", "output").put("emptyDir", new JsonObject()));
        addMount(volumeMounts, "output", "/out");
        addHostPath(volumes, "kernel-modules", "/lib/modules");
        addMount(volumeMounts, "kernel-modules", "/lib/modules");
        addHostPath(volumes, "kernel-debug", "/sys/kernel/debug");
        addMount(volumeMounts, "kernel-debug", "/sys/kernel/debug");
        addHostPath(volumes, "kernel-src", "/usr/src/kernels");
        addMount(volumeMounts, "kernel-src", "/usr/src/kernels");
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
            addMount(volumeMounts, "pull-secrets", "/etc/pull-secrets/");
        }
        addHostPath(volumes, "var-lib-kubelet", "/var/lib/kubelet");
        volumeMounts.add(new JsonObject()
              .put("name", "var-lib-kubelet").put("subPath", "config.json")
              // we cannot mount into /etc/pull-secrets
              .put("mountPath", "/etc/kubelet.config.json")
              .put("readOnly", true));

        String image = pod.getJsonObject("spec").getJsonArray("containers").getJsonObject(0).getString("image");
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

        sidecar.put("volumeMounts", volumeMounts);
        sidecar.put("env", env);
        addToPatch(patch, "/spec/containers/-", sidecar);
        addToPatch(patch, "/spec/shareProcessNamespace", true);
        for (Object volume : volumes) {
            addToPatch(patch, "/spec/volumes/-", volume);
        }
        return patch;
    }

    private void addEnvFromField(JsonArray env, String name, String path) {
        env.add(new JsonObject().put("name", name).put("valueFrom",
              new JsonObject().put("fieldRef", new JsonObject().put("fieldPath", path))));
    }

    private void addMount(JsonArray volumeMounts, String name, String path) {
        volumeMounts.add(new JsonObject().put("name", name).put("mountPath", path));
    }

    private void addHostPath(JsonArray volumes, String name, String path) {
        volumes.add(new JsonObject().put("name", name).put("hostPath", new JsonObject().put("path", path)));
    }

    private void addEnv(JsonArray env, String name, String value) {
        env.add(new JsonObject().put("name", name).put("value", value));
    }

    private void addToPatch(JsonArray patch, String path, Object value) {
        patch.add(new JsonObject().put("op", "add").put("path", path).put("value", value));
    }

    private boolean isEnabled(Object annotation) {
        if (annotation instanceof String) {
            String str = (String) annotation;
            return "enable".equalsIgnoreCase(str) ||
                  "enabled".equalsIgnoreCase(str) ||
                  "true".equalsIgnoreCase(str) ||
                  "yes".equalsIgnoreCase(str);
        } else if (annotation instanceof Boolean) {
            return (Boolean) annotation;
        } else {
            return false;
        }
    }
}