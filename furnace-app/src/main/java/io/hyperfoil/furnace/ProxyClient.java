package io.hyperfoil.furnace;

import javax.inject.Singleton;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@Path("/proxy")
@RegisterRestClient(configKey = "proxy-client")
@Singleton
public interface ProxyClient {
   @POST
   @Path("/register")
   long register(Proxy.Registration registration);
}