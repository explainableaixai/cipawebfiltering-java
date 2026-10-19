package com.alphaquantum.cipawebfiltering;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Client for the CIPA Web Filtering lookup API. Instances are immutable and thread-safe. */
public final class CipaWebFilteringClient {
  /** Client version. */
  public static final String VERSION = "1.0.0";
  /** Largest batch per request. */
  public static final int BATCH_MAX = 1000;
  private static final String DEFAULT_BASE_URL = "https://www.cipawebfiltering.com/api";
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final String apiKey;
  private final String baseUrl;
  private final HttpClient http;
  private final Duration timeout;
  private final ObjectMapper json = new ObjectMapper();

  /**
   * Client with the default endpoint and a 60 second timeout.
   *
   * @param apiKey API key
   */
  public CipaWebFilteringClient(String apiKey) {
    this(apiKey, DEFAULT_BASE_URL, Duration.ofSeconds(60));
  }

  /**
   * @param apiKey API key
   * @param baseUrl endpoint root, for tests or proxies
   * @param timeout request timeout
   */
  public CipaWebFilteringClient(String apiKey, String baseUrl, Duration timeout) {
    this.apiKey = apiKey == null ? "" : apiKey;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.timeout = timeout;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  private Map<String, Object> send(HttpRequest.Builder b) throws IOException, InterruptedException {
    HttpRequest req = b.timeout(timeout).header("X-API-Key", apiKey).header("Accept", "application/json")
        .header("User-Agent", "cipawebfiltering-java/" + VERSION).build();
    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    Map<String, Object> data;
    try {
      data = json.readValue(resp.body(), MAP);
    } catch (IOException e) {
      data = null;
    }
    if (resp.statusCode() / 100 != 2) {
      Object d = data == null ? null : (data.containsKey("detail") ? data.get("detail") : data.get("error"));
      throw new ApiException(resp.statusCode(), d == null ? resp.body() : d.toString());
    }
    if (data == null) throw new IOException("response is not a JSON object");
    return data;
  }

  /**
   * Classifies one domain or URL. One credit.
   *
   * @param domain domain or URL
   * @return the classification
   * @throws IOException on network errors
   * @throws InterruptedException if interrupted
   */
  public Classification classify(String domain) throws IOException, InterruptedException {
    if (domain == null || domain.isBlank()) throw new IllegalArgumentException("empty domain");
    String q = URLEncoder.encode(domain.trim(), StandardCharsets.UTF_8);
    return Classification.fromMap(send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/classify?url=" + q)).GET()));
  }

  /**
   * Classifies any number of domains in chunks of 1,000. One credit per domain.
   *
   * @param domains domains or URLs
   * @return classifications in input order
   * @throws IOException on network errors
   * @throws InterruptedException if interrupted
   */
  @SuppressWarnings("unchecked")
  public List<Classification> classifyMany(Collection<String> domains) throws IOException, InterruptedException {
    List<String> list = new ArrayList<>();
    for (String d : domains) if (d != null && !d.isBlank()) list.add(d.trim());
    List<Classification> out = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i += BATCH_MAX) {
      List<String> chunk = list.subList(i, Math.min(list.size(), i + BATCH_MAX));
      String body = json.writeValueAsString(Collections.singletonMap("urls", chunk));
      Map<String, Object> env = send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/classify"))
          .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
      Object rs = env.get("results");
      if (rs instanceof List) for (Object r : (List<Object>) rs) out.add(Classification.fromMap((Map<String, Object>) r));
    }
    return out;
  }

  /**
   * Service health. Free.
   *
   * @return decoded JSON
   * @throws IOException on network errors
   * @throws InterruptedException if interrupted
   */
  public Map<String, Object> health() throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/health")).GET());
  }

  /**
   * Database subscribers: key status and plan.
   *
   * @return decoded JSON
   * @throws IOException on network errors
   * @throws InterruptedException if interrupted
   */
  public Map<String, Object> accountStatus() throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + "/?action=status")).GET());
  }

  /**
   * Database subscribers: last update time and size of the database file.
   *
   * @return decoded JSON
   * @throws IOException on network errors
   * @throws InterruptedException if interrupted
   */
  public Map<String, Object> databaseInfo() throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + "/?action=database_info")).GET());
  }
}
