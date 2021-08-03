package io.hyperfoil.furnace;

public class Util {
   static boolean getBooleanEnv(String env, boolean defaultValue) {
       String value = System.getenv(env);
       return value == null ? defaultValue : "true".equalsIgnoreCase(value);
   }
}
