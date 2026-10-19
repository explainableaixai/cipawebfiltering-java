# cipawebfiltering (Java)

Java client for the CIPA Web Filtering lookup API. It classifies domains into web-filtering categories, one at a time or in batches of 1,000, and evaluates the result with a `Policy` that models how schools and libraries configure their filters.

```xml
<dependency>
  <groupId>io.github.explainableaixai</groupId>
  <artifactId>cipawebfiltering</artifactId>
  <version>1.0.0</version>
</dependency>
```

```groovy
implementation("io.github.explainableaixai:cipawebfiltering:1.0.0")
```

Java 11 or newer. The only runtime dependency is Jackson for JSON; HTTP goes through the JDK's own `java.net.http.HttpClient`. If you are setting up a JDK, [dev.java](https://dev.java) is the official starting point; the artifact itself is published on [Maven Central](https://central.sonatype.com/artifact/io.github.explainableaixai/cipawebfiltering).

## Where Java shows up in filtering

Java is everywhere in the back offices of education. Library management systems, student information systems, enterprise proxies, log pipelines on Kafka or Flink, and the Spring Boot services that glue them together. When any of these need to know "what kind of site is this?", for a report, a policy check or a block page, they need a client that is boring, thread-safe and easy to test. That is the goal of this library.

Three integration patterns cover most real projects:

1. **A servlet filter or Spring interceptor** in a proxy or portal that decides per request.
2. **A stream enrichment step** that adds categories to DNS, proxy or firewall events before they reach a dashboard or a data warehouse.
3. **A batch report** that classifies the distinct domains of a month and summarises them for an Internet Safety Policy review.

Each is shown below. Teams still deciding whether to run filtering infrastructure themselves at all can compare hosted and [on-site web filtering for schools](https://www.cipawebfiltering.com/solutions/on-premise-web-filtering-for-schools.php) first; this library works the same either way.

## The basics

```java
import com.alphaquantum.cipawebfiltering.*;

CipaWebFilteringClient client = new CipaWebFilteringClient(System.getenv("CWF_API_KEY"));
Classification r = client.classify("https://www.wikipedia.org/");

System.out.println(r.domain() + " " + r.categories());
System.out.println("found=" + r.found() + " method=" + r.method());
```

`classify` accepts a domain, hostname or full URL and costs one credit. `Classification` is immutable:

| Method | Returns |
| --- | --- |
| `domain()` | the domain as the service saw it |
| `rootDomain()` | the registrable domain that matched, or `null` |
| `categories()` | all web-filtering categories, unmodifiable, in no particular order |
| `contentCategory()` | a broader content label, or `null` |
| `country()` | country attribution, `null` when unknown (placeholders like `-` are removed) |
| `method()` | match method, `not_found` when there is no record |
| `confidence()` | 1.0 for a direct record |
| `found()` | `true` when at least one category exists |

`CipaWebFilteringClient` is immutable and thread-safe. Create one per application and share it. A second constructor takes a base URL and a timeout, which is how tests point it at a local stub server:

```java
new CipaWebFilteringClient(key, "http://127.0.0.1:8089", Duration.ofSeconds(5));
```

## Batches

```java
List<Classification> results = client.classifyMany(hosts);
```

`classifyMany` drops blank entries, splits the input into chunks of `CipaWebFilteringClient.BATCH_MAX` (1,000) and posts each chunk, returning the classifications in order. Each domain costs one credit. When a chunk would exceed the key's remaining credits, the service rejects it with HTTP 429 before doing any work and the client throws `ApiException` with `status() == 429`.

## Policies

```java
Policy p = Policy.district();
boolean blocked = p.blocks(r.categories());
List<String> because = p.matches(r.categories());
```

A `Policy` is a set of blocked category names plus a `Policy.Rule`:

- `ANY`: block if any category of the site is in the policy.
- `MAJORITY`: block if more than half of the site's categories are.
- `ALL`: block only if every category is.

Presets, each adding to the one before:

```java
Policy.statutoryCore();      // Adult
Policy.withCircumvention();  // + VPN / Proxy / Anonymizers
Policy.broadHarm();          // + Lingerie/Swimsuits, Violence, Weapons, Hate/Discrimination, Illegal Drugs, Gambling
Policy.district();           // + Alcohol, Tobacco, Dating, File Sharing/P2P, Remote Access, URL Shorteners, Parked, Cryptocurrency, Phishing
Policy.restrictive();        // + Social, Gaming, Streaming, Forums, Community, Shopping, Marketplaces, Podcasts, Sports, AI / LLM Tools, Email, Blogs
```

`withRule(Policy.Rule.MAJORITY)` returns a copy with another rule. The category groups are public constants (`Policy.BROAD_HARM`, `Policy.INSTRUCTIONAL` and so on), and `new Policy(collection, rule)` builds any custom set.

### Why three rules

Sites carry several categories. A school athletics site is Sports & Recreation and Education/Reference; a gun shop's catalogue is Shopping and Weapons; a regional newspaper is News/Media plus whatever its sections cover. Under `ANY`, a single sensitive label blocks the whole site.

An October 2026 simulation measured the effect over 103 million categorised domains. With `ANY`, the typical district preset blocked 4.59% of domains and 0.30% of the 16.3 million domains carrying an education, health, government, news, science or civic label. The restrictive preset blocked 19.1% of domains and 7.6% of those labelled domains, including 2.2% of `.edu` and 4.2% of government domains. Switching the restrictive preset to `MAJORITY` removed about 94% of that collateral. The statutory core alone blocked 0.92% and touched 0.048%.

So: use `withCircumvention()` with `ANY` as a fixed floor, and treat everything else as a separate, configurable policy, usually with `MAJORITY`.

## Spring Boot wiring

Expose the client and your policies as beans, with the discretionary part in `application.yml` so a policy change is a configuration change, not a release:

```yaml
cwf:
  api-key: ${CWF_API_KEY}
  discretionary:
    rule: MAJORITY
    categories: [Gaming, Social Networking, Streaming Media]
```

```java
@Configuration
class FilteringConfig {
  @Bean
  CipaWebFilteringClient cwfClient(@Value("${cwf.api-key}") String key) {
    return new CipaWebFilteringClient(key);
  }

  @Bean
  Policy floorPolicy() {
    return Policy.withCircumvention(); // fixed in code on purpose
  }

  @Bean
  Policy discretionaryPolicy(@Value("${cwf.discretionary.categories}") List<String> cats,
                             @Value("${cwf.discretionary.rule}") Policy.Rule rule) {
    return new Policy(cats, rule);
  }
}
```

Keeping the floor in code and the rest in configuration mirrors the legal situation: the floor is fixed, everything above it is a board decision that changes over time.

## Pattern 1: a servlet filter

```java
public final class CategoryFilter implements jakarta.servlet.Filter {
  private final CipaWebFilteringClient client;
  private final Policy floor = Policy.withCircumvention();
  private final Cache<String, List<String>> cache = Caffeine.newBuilder()
      .maximumSize(200_000).expireAfterWrite(Duration.ofHours(6)).build();

  public CategoryFilter(CipaWebFilteringClient client) { this.client = client; }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
    String target = req.getParameter("url");
    if (target != null) {
      String host = URI.create(target).getHost();
      List<String> cats = cache.get(host, h -> {
        try { return client.classify(h).categories(); }
        catch (Exception e) { return List.of(); } // fail open; the resolver still enforces the floor
      });
      if (floor.blocks(cats)) {
        ((HttpServletResponse) res).sendError(403, "Blocked by school policy: " + floor.matches(cats));
        return;
      }
    }
    chain.doFilter(req, res);
  }
}
```

Caffeine is not a dependency of this library; any cache works, including a plain `ConcurrentHashMap` with a timestamp per entry. The important parts are the cache (most traffic goes to a few thousand hosts) and the explicit decision about what a failed lookup means.

## Pattern 2: enriching a stream

DNS and proxy events arrive at thousands per second, but distinct hosts are few. Enrich with a cache and a periodic batch for misses:

```java
// collect misses for one second, then classify them together
List<String> misses = drainMisses();
for (Classification c : client.classifyMany(misses)) {
  cache.put(c.domain(), c.categories());
}
```

The enriched event then carries `categories` as an array field. Downstream, a dashboard can count by category without anyone calling the API again. Remember that a domain can carry several categories, so category totals add up to more than the event count; say so on the dashboard.

## Pattern 3: the yearly policy review

Boards review their Internet Safety Policy, and most of them want data. A monthly job that classifies the distinct domains students reached, and counts what each preset would have blocked, turns the review into a short table:

```java
Map<String, Long> wouldBlock = new LinkedHashMap<>();
Map<String, Policy> candidates = Map.of(
    "current", Policy.district(),
    "restrictive/any", Policy.restrictive(),
    "restrictive/majority", Policy.restrictive().withRule(Policy.Rule.MAJORITY));
candidates.forEach((name, pol) ->
    wouldBlock.put(name, results.stream().filter(c -> pol.blocks(c.categories())).count()));
```

Pair the numbers with the arguments on both sides. The page on the [benefits of internet filtering in schools](https://www.cipawebfiltering.com/guides/internet-filtering-schools-pros-and-cons.php) and its counterarguments is a balanced read for board members, and the category descriptions on [what is k12 internet filtering](https://www.cipawebfiltering.com/solutions/web-filtering-categories-taxonomy.php) help them understand what each label means.

## Libraries

Public libraries are subject to CIPA when they take E-Rate discounts, but the law treats adults differently: the Supreme Court's 2003 decision relied on adults being able to ask for the filter to be disabled. In code that means the floor policy applies to minors' sessions, and an adult session may legitimately bypass it. Many library systems therefore keep two policies, one for children's terminals and one for adult terminals, and a staff override with an audit trail. The `Policy` class is small enough that keeping several side by side costs nothing.

## Errors

`ApiException` (unchecked) carries the HTTP status:

| `status()` | Meaning |
| --- | --- |
| 401 | missing or invalid API key |
| 429 | not enough credits for this request |
| 502 | classification backend briefly unavailable |

Network problems and timeouts surface as `IOException`; interruption as `InterruptedException`. The default timeout is 60 seconds; set a shorter one through the second constructor for request paths. The client never retries automatically, so a batch whose response was lost is not charged twice.

## Health and account endpoints

```java
Map<String, Object> health = client.health();          // free, no key: status and loaded record counts
Map<String, Object> status = client.accountStatus();   // database subscribers: key and plan
Map<String, Object> info = client.databaseInfo();      // last update time and size of the database file
```

`health()` fits a Spring Boot `HealthIndicator` directly. It costs no credits and needs no key, so it is safe to call from a readiness probe every few seconds; if it fails, your service can report "category data unavailable" instead of failing every request that needs a lookup.

## Testing your integration

The library's own tests start a `com.sun.net.httpserver.HttpServer` on a free local port, answer with canned JSON, and point the client at it. Do the same in your tests; no network, no key, no credits:

```java
HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
server.createContext("/", ex -> {
  byte[] body = "{\"sni\":\"example.org\",\"web_filtering_category\":\"Shopping|Weapons\",\"method\":\"root_domain_direct\",\"confidence\":1.0}"
      .getBytes(StandardCharsets.UTF_8);
  ex.sendResponseHeaders(200, body.length);
  ex.getResponseBody().write(body);
  ex.close();
});
server.start();
CipaWebFilteringClient client =
    new CipaWebFilteringClient("test", "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));
assertTrue(Policy.broadHarm().blocks(client.classify("example.org").categories()));
assertFalse(Policy.broadHarm().withRule(Policy.Rule.MAJORITY).blocks(client.classify("example.org").categories()));
```

For pure policy tests you do not even need the server: `Policy.blocks(String...)` takes category names directly.

## Context: the law, briefly

The [cipa law](https://www.cipawebfiltering.com/guides/what-is-cipa.php) requires US schools and libraries that take E-Rate discounts to block obscene images, child sexual abuse material and, for minors, material harmful to minors, and to adopt an Internet Safety Policy. "Harmful to minors" is a sexual-content standard. Everything else that districts block is their own choice, which is why only `statutoryCore()` corresponds to the statute and the other presets are labelled as district choices. No agency certifies products; a vendor offering [cipa compliant filtering](https://www.cipawebfiltering.com/) is describing what its product lets a school do.

Two practical topics come up in every school project. Managed devices that go home need filtering that follows them; the notes on choosing a [chromebook filter for schools](https://www.cipawebfiltering.com/solutions/chromebook-1to1-device-filtering.php) cover the device side. And budget: the comparison of free and paid options when picking the [best web filter for school district](https://www.cipawebfiltering.com/guides/free-vs-paid-web-filtering-schools.php) use explains what the cheaper routes leave out.

## FAQ

**Does the library block traffic?**
No. It returns categories and decisions; your proxy, resolver or application enforces them.

**Is there a primary category?**
No. The service returns labels in no meaningful order, and the library treats them as a set.

**Does it work on Android?**
The client uses `java.net.http`, which Android does not ship. On Android, call the HTTP endpoints with OkHttp; the request and response formats are the same.

**Which Java versions?**
The artifact is compiled for Java 11 (`maven.compiler.release` 11), and the tests run on Java 17. It uses no preview features and no reflection tricks, so it also runs on newer LTS releases.

**How many credits does a stream enrichment job use?**
One per distinct domain that misses the cache. With a six-hour cache, a busy district typically classifies tens of thousands of domains a day, not millions.

**What about unknown domains?**
`found()` is `false` and `categories()` is empty, so no policy blocks them. Decide explicitly what your application does with them.

## License

MIT, Copyright (c) 2026 Alpha Quantum.
