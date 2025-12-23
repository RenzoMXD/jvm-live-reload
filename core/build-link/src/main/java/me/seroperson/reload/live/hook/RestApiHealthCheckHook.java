package me.seroperson.reload.live.hook;

import java.net.HttpURLConnection;
import java.net.URI;
import me.seroperson.reload.live.build.BuildLogger;

/**
 * Health check hook that uses REST API calls to determine server health.
 *
 * <p>This hook performs health checks by making HTTP GET requests to a "/health" endpoint on the
 * server. The server is considered healthy if the endpoint returns a 200 status code within the
 * timeout period.
 */
interface RestApiHealthCheckHook extends HealthCheckHook {

  default int isHealthy(BuildLogger logger, String path, String host, int port) {
    try {
      var url = new URI("http://" + host + ":" + port + path).toURL();
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestProperty("Connection", "close");
      connection.setReadTimeout(500);
      connection.setConnectTimeout(500);
      try {
        var responseCode = connection.getResponseCode();
        if (responseCode == 404) {
          return 404;
        }
        var isSuccess = responseCode >= 200 && responseCode < 300;
        if (isSuccess) {
          return 1;
        } else {
          return 0;
        }
      } catch (java.net.ConnectException e) {
        return -1;
      } catch (java.io.IOException e) {
        return -1;
      } catch (Exception e) {
        logger.error("Error during requesting health-check", e);
        return -1;
      } finally {
        connection.disconnect();
      }
    } catch (Exception e) {
      return -1;
    }
  }
}
