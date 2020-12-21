package io.hyperfoil.furnace;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;

@ApplicationScoped
@Startup
@Path("/controller")
public class Controller {
   private static final Logger log = Logger.getLogger(Controller.class);
   private static final String POD_NAME = System.getenv("POD_NAME");
   private static final String POD_NAMESPACE = System.getenv("POD_NAMESPACE");

   @Inject
   @RestClient
   ProxyClient proxy;

   private final ScheduledExecutorService leaseExecutor = Executors.newScheduledThreadPool(1);
   private final ExecutorService processingExecutor = Executors.newSingleThreadExecutor();
   private String mountPoint;
   private Process perfRecord;
   private Process perfScript;
   private Process stackCollapse;
   private Process flamegraph;
   private String error;
   private long recordStart, recordEnd;

   @PostConstruct
   public synchronized void registerSelf() {
      if (System.getenv().get("FURNACE_SIDECAR") == null) {
         return;
      }
      if (mountPoint == null) {
         mountMainImage(System.getenv("FURNACE_MAIN_IMAGE"));
      }
      Proxy.Registration registration = new Proxy.Registration();
      registration.podName = POD_NAME;
      registration.namespace = POD_NAMESPACE;
      registration.ip = System.getenv("POD_IP");
      try {
         registration.port = Integer.parseInt(System.getenv("QUARKUS_HTTP_PORT"));
      } catch (NumberFormatException e) {
         // ignore;
      }
      try {
         long lease = proxy.register(registration);
         if (lease <= 0) {
            log.error("Registration " + registration + " was not accepted.");
         } else {
            log.infof("Registered with lease of %d seconds", lease);
            leaseExecutor.schedule(this::registerSelf, lease / 2, TimeUnit.MILLISECONDS);
         }
      } catch (Exception e) {
         log.error("Failed to register, retrying in 30 seconds", e);
         leaseExecutor.schedule(this::registerSelf, 30000, TimeUnit.MILLISECONDS);
      }
   }

   private void mountMainImage(String mainImage) {
      try {
         int rc = new ProcessBuilder().command("podman", "run", "-d", "--rm", "--name", "main-container",
               "--cgroup-manager=cgroupfs", "--entrypoint", "tail", mainImage, "-f", "/dev/null")
               .inheritIO().start().waitFor();
         if (rc != 0) {
            log.errorf("Failed to start podman: %d", rc);
         } else {
            rc = new ProcessBuilder().command("podman", "mount", "main-container")
                  .inheritIO().redirectOutput(new File("/out/mountpoint"))
                  .start().waitFor();
            if (rc != 0) {
               log.errorf("Failed to find podman mount point: %d", rc);
            } else {
               mountPoint = Files.readString(Paths.get("/out/mountpoint"));
               log.infof("Container mount point is %s", mountPoint);
            }
         }
      } catch (IOException | InterruptedException e) {
         log.error("Failed to start podman", e);
      }
   }

   @PreDestroy
   public void destroy() {
      processingExecutor.shutdown();
      leaseExecutor.shutdown();
   }

   @GET
   @Path("ready")
   public Response ready() {
      return mountPoint == null ? Response.status(404).build() : Response.ok().build();
   }

   @GET
   @Path("status")
   public synchronized String status() {
      if (perfRecord != null) {
         return "perf record";
      } else if (perfScript != null) {
         return "perf script";
      } else if (stackCollapse != null) {
         return "stackcollapse";
      } else if (flamegraph != null) {
         return "flamegraph";
      }
      return error == null ? "idle" : error;
   }

   @POST
   @Path("start")
   public synchronized void start() {
      String status = status();
      if (!"idle".equals(status)) {
         throw new WebApplicationException("Already running: " + status);
      }
      try {
         File file = new File("/out/perf.svg");
         if (file.exists()) {
            Files.delete(file.toPath());
         }
      } catch (IOException e) {
         error = "Cannot delete old chart.";
         throw new WebApplicationException(error, e);
      }
      try {
         perfRecord = new ProcessBuilder().command("perf", "record", "-g", "-a", "-F", "99", "-o", "/out/perf.data").inheritIO().start();
         recordStart = System.currentTimeMillis();
      } catch (IOException e) {
         error = "Failed to start `perf record`";
         throw new WebApplicationException(error, e);
      }
   }

   @POST
   @Path("stop")
   public synchronized void stop(@QueryParam("symfs") boolean symfs,
         @QueryParam("width") int width, @QueryParam("colors") String colors,
         @QueryParam("inverted") @DefaultValue("true") boolean inverted) {
      if (!"perf record".equals(status())) {
         throw new WebApplicationException("Not running: current status is: " + status());
      }
      recordEnd = System.currentTimeMillis();
      perfRecord.destroy();
      try {
         int rc = perfRecord.waitFor();
         if (rc == 0 || rc == 143) {
            List<String> command = new ArrayList<>(Arrays.asList("perf", "script", "-i", "/out/perf.data", "--kallsyms=/proc/kallsyms"));
            if (symfs) {
               command.add("--symfs=" + mountPoint);
            }
            perfScript = new ProcessBuilder().command(command)
                  .inheritIO().redirectOutput(new File("/out/perf.script")).start();
            processingExecutor.submit(() -> process(width, colors, inverted));
         } else {
            error = "Non-zero return code from `perf record`: " + rc;
            throw new WebApplicationException(error);
         }
      } catch (InterruptedException e) {
         error = "Interrupted waiting for `perf record` to finish.";
         throw new WebApplicationException(error, e);
      } catch (IOException e) {
         error = "Failed to start `perf script`";
         throw new WebApplicationException(error, e);
      } finally {
         perfRecord = null;
      }
   }

   private synchronized void process(int width, String colors, boolean inverted) {
      try {
         int rc2 = perfScript.waitFor();
         if (rc2 != 0) {
            error = "Non zero return code from `perf script`: " + rc2;
            log.error(error);
            return;
         }
      } catch (InterruptedException e) {
         error = "Interrupted waiting for `perf script`";
         log.error(error, e);
         return;
      } finally {
         perfScript = null;
      }
      try {
         stackCollapse = new ProcessBuilder().command("/root/FlameGraph/stackcollapse-perf.pl", "/out/perf.script")
               .inheritIO().redirectOutput(new File("/out/perf.collapsed")).start();
         int rc3 = stackCollapse.waitFor();
         if (rc3 != 0) {
            error = "Non-zero return code from stackcollapse-perf.pl: " + rc3;
            log.error(error);
            return;
         }
      } catch (InterruptedException e) {
         error = "Interrupted waiting for stackcollapse-perf.pl";
         log.error(error, e);
         return;
      } catch (IOException e){
         error = "Failed to start `stackcollapse-perf.pl`";
         log.error(error, e);
         return;
      } finally {
         stackCollapse = null;
      }
      try {
         log.infof("Creating flamegraph, width: %d, colors: %s, inverted: %s", width, colors, inverted);
         List<String> command = new ArrayList<>();
         command.add("/root/FlameGraph/flamegraph.pl");
         if (width > 0) {
            command.add("--width");
            command.add(String.valueOf(width));
         }
         if (colors != null && !colors.isEmpty()) {
            command.add("--colors");
            command.add(colors);
         }
         if (inverted) {
            command.add("--inverted");
         }
         SimpleDateFormat df = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
         Date startDate = new Date(recordStart);
         Date endDate = new Date(recordEnd);
         command.add("--title");
         command.add(POD_NAMESPACE + "/" + POD_NAME + " " + df.format(startDate) + " - " + df.format(endDate));
         command.add("/out/perf.collapsed");
         flamegraph = new ProcessBuilder().command(command)
               .inheritIO().redirectOutput(new File("/out/perf.svg")).start();
         int rc4 = flamegraph.waitFor();
         if (rc4 != 0) {
            error = "Non-zero return code from flamegraph.pl: " + rc4;
            log.error(error);
         }
      } catch (InterruptedException e) {
         error = "Interrupted waiting for flamegraph.pl";
         log.error(error, e);
      } catch (IOException e){
         error = "Failed to start `flamegraph.pl`";
         log.error(error, e);
      } finally {
         flamegraph = null;
      }
   }

   @GET
   @Path("chart")
   @Produces("image/svg+xml")
   public Response chart() {
      File file = new File("/out/perf.svg");
      if (file.exists()) {
         return Response.ok(file).build();
      } else {
         return Response.status(404).build();
      }
   }
}
