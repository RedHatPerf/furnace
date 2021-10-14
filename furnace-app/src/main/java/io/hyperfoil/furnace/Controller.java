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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
   private static final String AUTOSTART = System.getenv("AUTOSTART");
   private static final String AUTOSTART_DELAY = System.getenv("AUTOSTART_DELAY");
   private static final String AUTOSTOP = System.getenv("AUTOSTOP");
   private static final String AUTORESTART = System.getenv("AUTORESTART");
   private static final String PROCESS_PATTERN = System.getenv("PROCESS_PATTERN");
   private static final String PULL_IMAGE = System.getenv("PULL_IMAGE");

   @Inject
   @RestClient
   ProxyClient proxy;

   private final ScheduledExecutorService leaseExecutor = Executors.newSingleThreadScheduledExecutor();
   private final ScheduledExecutorService timedExecutor = Executors.newSingleThreadScheduledExecutor();
   private final ExecutorService processingExecutor = Executors.newSingleThreadExecutor();
   private String mountPoint;
   private Process perfRecord;
   private Process perfScript;
   private Process stackCollapse;
   private Process flamegraph;
   private String error;
   private long recordStart, recordEnd;

   @PostConstruct
   public synchronized void init() {
      if (System.getenv().get("FURNACE_SIDECAR") == null) {
         return;
      }
      if (AUTOSTART != null) {
         if (checkAutostart()) {
            log.info("Auto-starting recording");
            Integer stop = null;
            if (AUTOSTOP != null) {
               try {
                  stop = Integer.parseInt(AUTOSTOP);
               } catch (NumberFormatException e) {
                  log.error("Cannot parse AUTOSTOP=" + AUTOSTOP + " into integer");
               }
            } else if (AUTORESTART != null) {
               Integer restart = null;
               try {
                  restart = Integer.parseInt(AUTORESTART);
               } catch (NumberFormatException e) {
                  log.errorf("Cannot parse AUTORESTART=%s", AUTORESTART);
               }
               if (restart != null) {
                  timedExecutor.scheduleWithFixedDelay(() -> stop(true, 0, null, true).whenComplete(
                              (ignore1, ignore2) -> start(null, true, 0, null, true, null)),
                        restart, restart, TimeUnit.SECONDS);
               }
            }
            Integer delay = null;
            if (AUTOSTART_DELAY != null) {
               try {
                  delay = Integer.parseInt(AUTOSTART_DELAY);
               } catch (NumberFormatException e) {
                  log.errorf("Cannot parse AUTOSTART_DELAY=%s", AUTOSTART_DELAY);
               }
            }
            if (delay != null) {
               Integer myStop = stop;
               timedExecutor.schedule(() -> start(myStop, true, 0, null, true, null), delay, TimeUnit.SECONDS);
            } else {
               start(stop, true, 0, null, true, null);
            }
         }
      }
      if (mountPoint == null) {
         mountMainImage(System.getenv("FURNACE_MAIN_IMAGE"));
      }
      registerSelf();
   }

   private synchronized void registerSelf() {
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

   private boolean checkAutostart() {
      for (String part : AUTOSTART.split(",")) {
         int asteriskIndex = part.indexOf('*');
         if (asteriskIndex < 0) {
            if (POD_NAME.equals(part)) {
               return true;
            }
         } else {
            String prefix = part.substring(0, asteriskIndex);
            String suffix = part.substring(asteriskIndex + 1);
            if (POD_NAME.startsWith(prefix) && POD_NAME.endsWith(suffix)) {
               return true;
            }
         }
      }
      return false;
   }

   private void mountMainImage(String mainImage) {
      if ("false".equals(PULL_IMAGE)) {
         log.infof("Pulling image %s disabled.", mainImage);
         return;
      }
      File lockFile = new File("/containers/storage/vfs-images/images.lock");
      if (!lockFile.exists()) {
         lockFile.getParentFile().mkdirs();
         try {
            lockFile.createNewFile();
         } catch (IOException e) {
            log.error("Failed to initialize VFS images lock file");
         }
      }
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
      return mountPoint != null || "false".equals(PULL_IMAGE) ? Response.ok().build() : Response.status(404).build();
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
   public synchronized void start(@QueryParam("stop") Integer stop,
                                  @QueryParam("symfs") boolean symfs,
                                  @QueryParam("width") int width,
                                  @QueryParam("colors") String colors,
                                  @QueryParam("inverted") @DefaultValue("true") boolean inverted,
                                  @QueryParam("processPattern") String processPattern) {
      String status = status();
      if (!"idle".equals(status)) {
         throw new WebApplicationException("Already running: " + status);
      }
      backupOldChart();
      if (processPattern == null) {
         processPattern = PROCESS_PATTERN;
      }
      List<String> command = new ArrayList<>(Arrays.asList("perf", "record", "-g", "-F", "99", "-o", "/out/perf.data"));
      if (processPattern == null) {
         command.add("-a");
      } else {
         try {
            int rc = new ProcessBuilder().command("pgrep", processPattern)
                  .inheritIO().redirectOutput(new File("/out/pids"))
                  .start().waitFor();
            if (rc != 0) {
               log.errorf("Failed to find PIDs for pattern %s: %d", processPattern, rc);
               return;
            } else {
               List<String> pids = Files.readAllLines(Paths.get("/out/pids"));
               if (pids.isEmpty()) {
                  log.errorf("No PIDs for pattern %s", processPattern);
                  return;
               }
               log.infof("Recording data from pids %s", pids);
               command.add("-p");
               command.add(String.join(",", pids));
            }
         } catch (InterruptedException | IOException e) {
            log.errorf(e, "Failed to find PIDs for pattern %s", processPattern);
         }
      }
      try {
         perfRecord = new ProcessBuilder().command(command).inheritIO().start();
         recordStart = System.currentTimeMillis();
      } catch (IOException e) {
         error = "Failed to start `perf record`";
         throw new WebApplicationException(error, e);
      }
      if (stop != null) {
         log.infof("The recording will automatically stop in %d seconds.", stop);
         timedExecutor.schedule(() -> this.stop(symfs, width, colors, inverted), stop, TimeUnit.SECONDS);
      }
   }

   @POST
   @Path("stop")
   public synchronized CompletionStage<Void> stop(@QueryParam("symfs") boolean symfs,
                                                  @QueryParam("width") int width,
                                                  @QueryParam("colors") String colors,
                                                  @QueryParam("inverted") @DefaultValue("true") boolean inverted) {
      if (!"perf record".equals(status())) {
         throw new WebApplicationException("Not running: current status is: " + status());
      }
      if (!perfRecord.isAlive()) {
         throw new WebApplicationException("Already stopping...");
      }
      recordEnd = System.currentTimeMillis();
      perfRecord.destroy();
      CompletableFuture<Void> future = new CompletableFuture<>();
      processingExecutor.submit(() -> {
         try {
            int rc = perfRecord.waitFor();
            if (rc == 0 || rc == 143) {
               List<String> command = new ArrayList<>(Arrays.asList("perf", "script", "-i", "/out/perf.data", "--kallsyms=/proc/kallsyms"));
               if (symfs) {
                  command.add("--symfs=" + mountPoint);
               }
               perfScript = new ProcessBuilder().command(command)
                     .inheritIO().redirectOutput(new File("/out/perf.script")).start();
               process(width, colors, inverted);
            } else {
               error = "Non-zero return code from `perf record`: " + rc;
               future.completeExceptionally(new WebApplicationException(error));
            }
         } catch (InterruptedException e) {
            error = "Interrupted waiting for `perf record` to finish.";
            future.completeExceptionally(new WebApplicationException(error, e));
         } catch (IOException e) {
            error = "Failed to start `perf script`";
            future.completeExceptionally(new WebApplicationException(error, e));
         } finally {
            synchronized (this) {
               perfRecord = null;
            }
            future.complete(null);
         }
      });
      return future;
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
      String[] scripts = new File("/scripts").list();
      if (scripts != null) {
         Arrays.sort(scripts);
         for (String filename : scripts) {
            if (filename.startsWith(".") || !filename.endsWith(".sh")) {
               log.infof("Ignoring non-shell script %s", filename);
               continue;
            }
            File file = new File("/scripts", filename);
            if (file.isHidden() || !file.isFile() || !file.canExecute()) continue;
            log.infof("Executing script %s", file.toString());
            try {
               Runtime.getRuntime().exec("bash -c " + file).waitFor();
            } catch (IOException | InterruptedException e) {
               log.error("Failed to execute script", e);
            }
         }
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
         File target = new File("/out/perf.svg");
         flamegraph = new ProcessBuilder().command(command)
               .inheritIO().redirectOutput(target).start();
         int rc4 = flamegraph.waitFor();
         if (rc4 != 0) {
            error = "Non-zero return code from flamegraph.pl: " + rc4;
            log.error(error);
         } else {
            log.infof("Written flamegraph to %s", target.toString());
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

   private void backupOldChart() {
      File target = new File("/out/perf.svg");
      if (!target.exists()) {
         return;
      }
      int counter = 0;
      File backup = new File("/out/perf." + counter + ".svg");
      while (backup.exists()) {
         ++counter;
         backup = new File("/out/perf." + counter + ".svg");
      }
      try {
         log.infof("Backing up %s to %s", target.toString(), backup.toString());
         Files.move(target.toPath(), backup.toPath());
      } catch (IOException e) {
         log.errorf(e, "Failed to backup old chart to %s", backup.toString());
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
