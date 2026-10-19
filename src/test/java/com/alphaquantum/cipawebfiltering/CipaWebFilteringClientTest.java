package com.alphaquantum.cipawebfiltering;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CipaWebFilteringClientTest {
  private HttpServer server;

  private CipaWebFilteringClient stub(String key) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      String body;
      int status = 200;
      if (!"k".equals(ex.getRequestHeaders().getFirst("X-API-Key"))) {
        status = 401;
        body = "{\"detail\":\"Invalid or missing API key\"}";
      } else if ("POST".equals(ex.getRequestMethod())) {
        body = "{\"results\":[{\"sni\":\"a.com\",\"web_filtering_category\":\"Adult\",\"method\":\"root_domain_direct\",\"confidence\":1.0,\"country\":\"-\"}],\"count\":1}";
      } else {
        body = "{\"sni\":\"khanacademy.org\",\"root_domain\":\"khanacademy.org\",\"web_filtering_category\":\"Education/Reference\",\"country\":\"United States\",\"method\":\"root_domain_direct\",\"confidence\":1.0}";
      }
      byte[] out = body.getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(status, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    server.start();
    return new CipaWebFilteringClient(key, "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void classifyAndBatch() throws Exception {
    CipaWebFilteringClient c = stub("k");
    Classification r = c.classify("khanacademy.org");
    assertTrue(r.found());
    assertEquals(List.of("Education/Reference"), r.categories());
    List<Classification> rs = c.classifyMany(List.of("a.com"));
    assertEquals(1, rs.size());
    assertNull(rs.get(0).country());
  }

  @Test
  void badKey() throws Exception {
    CipaWebFilteringClient c = stub("x");
    ApiException e = assertThrows(ApiException.class, () -> c.classify("a.com"));
    assertEquals(401, e.status());
  }

  @Test
  void rules() {
    assertTrue(Policy.statutoryCore().blocks("Adult", "Dating"));
    assertFalse(Policy.statutoryCore().withRule(Policy.Rule.MAJORITY).blocks("Adult", "Dating"));
    assertTrue(Policy.district().withRule(Policy.Rule.ALL).blocks("Adult", "Dating"));
    assertFalse(Policy.restrictive().blocks("Education/Reference"));
    assertEquals(List.of("Shopping", "Gaming"), Classification.splitCategories("Shopping| shopping |Gaming"));
  }
}
