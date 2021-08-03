package io.hyperfoil.furnace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.jboss.resteasy.annotations.cache.NoCache;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;

@ApplicationScoped
@Path("/proxy")
public class Proxy {
   private static final Logger log = Logger.getLogger(Proxy.class);

   private static final long LEASE = TimeUnit.SECONDS.toMillis(Long.getLong("furnace.lease", 30));

   private final Timer timer = new Timer(true);
   private final List<Registration> registered = new ArrayList<>();
   private final Map<Registration, ResteasyClient> clients = new HashMap<>();

   @PostConstruct
   public void init() {
      timer.scheduleAtFixedRate(new TimerTask() {
         @Override
         public void run() {
            long now = System.currentTimeMillis();
            synchronized (Proxy.this) {
               registered.removeIf(r -> r.expires < now);
               for (Iterator<Map.Entry<Registration, ResteasyClient>> iterator = clients.entrySet().iterator(); iterator.hasNext(); ) {
                  Map.Entry<Registration, ResteasyClient> entry = iterator.next();
                  if (entry.getKey().expires < now) {
                     entry.getValue().close();
                     iterator.remove();
                  }
               }
            }
         }
      }, 0, LEASE);
   }

   @POST
   @Path("register")
   public synchronized long register(Registration registration) {
      if (registration.podName == null || registration.namespace == null || registration.ip == null) {
         log.error("Invalid registration: " + registration);
         return -1;
      }
      if (registration.port <= 0) {
         registration.port = 12380;
      }
      registration.expires = System.currentTimeMillis() + LEASE;
      registered.removeIf(r -> r.podName.equals(registration.podName) && r.namespace.equals(registration.namespace));
      registered.add(registration);
      return LEASE;
   }

   @GET
   @Path("registered")
   @NoCache
   public synchronized List<Registration> leases() {
      return new ArrayList<>(registered);
   }

   private synchronized ControllerClient controller(String namespace, String pod) {
      Registration registration = registered.stream()
            .filter(r -> r.namespace.equals(namespace) && r.podName.equals(pod))
            .findFirst().orElseThrow(() -> new WebApplicationException("No controller for ns: " + namespace + " pod: " + pod));
      ResteasyClient client = clients.computeIfAbsent(registration, r -> (ResteasyClient) ResteasyClientBuilder.newClient());
      return client.target("http://" + registration.ip + ":" + registration.port).proxy(ControllerClient.class);
   }

   @POST
   @Path("start")
   public void start(@QueryParam("namespace") String namespace, @QueryParam("pod") String pod) {
      controller(namespace, pod).start();
   }

   @POST
   @Path("stop")
   public void stop(@QueryParam("namespace") String namespace, @QueryParam("pod") String pod,
                    @QueryParam("symfs") @DefaultValue("false") boolean symfs,
                    @QueryParam("width") int width, @QueryParam("colors") String colors,
                    @QueryParam("inverted") @DefaultValue("true") boolean inverted) {
      controller(namespace, pod).stop(symfs, width, colors, inverted);
   }

   @GET
   @Path("status")
   @Produces(MediaType.TEXT_PLAIN)
   @NoCache
   public String status(@QueryParam("namespace") String namespace, @QueryParam("pod") String pod) {
      return controller(namespace, pod).status();
   }

   @GET
   @Path("chart")
   @NoCache
   public Response chart(@QueryParam("namespace") String namespace, @QueryParam("pod") String pod, @QueryParam("download") boolean download) {
      Response.ResponseBuilder response = Response.ok(controller(namespace, pod).chart().getEntity(), new MediaType("image", "svg+xml"));
      if (download) {
         response.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + namespace + "_" + pod + ".svg");
      }
      return response.build();
   }

   public static class Registration {
      public String podName;
      public String namespace;
      public String ip;
      public int port;
      public long expires;

      public static Registration create(String podName, String namespace, String ip, int port, long expires) {
         Registration r = new Registration();
         r.podName = podName;
         r.namespace = namespace;
         r.ip = ip;
         r.port = port;
         r.expires = expires;
         return r;
      }

      @Override
      public String toString() {
         return "Registration{" +
               "podName='" + podName + '\'' +
               ", namespace='" + namespace + '\'' +
               ", ip='" + ip + '\'' +
               ", port=" + port +
               ", expires=" + expires +
               '}';
      }
   }
}
