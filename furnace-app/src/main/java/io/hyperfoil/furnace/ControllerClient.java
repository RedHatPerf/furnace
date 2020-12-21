package io.hyperfoil.furnace;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

@Path("/controller")
public interface ControllerClient {
   @POST
   @Path("start")
   void start();

   @POST
   @Path("stop")
   void stop(@QueryParam("symfs") boolean symfs, @QueryParam("width") int width, @QueryParam("colors") String colors, @QueryParam("inverted") boolean inverted);

   @GET
   @Path("status")
   String status();

   @GET
   @Path("chart")
   Response chart();
}
