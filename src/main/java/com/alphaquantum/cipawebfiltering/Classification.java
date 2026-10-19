package com.alphaquantum.cipawebfiltering;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** One classified domain. Categories have no particular order; country is null when unknown. */
public final class Classification {
  private static final Set<String> PLACEHOLDERS = Set.of("", "-", "n/a", "na", "unknown", "none", "null");

  private final String domain;
  private final String rootDomain;
  private final List<String> categories;
  private final String contentCategory;
  private final String country;
  private final String method;
  private final double confidence;

  private Classification(String domain, String rootDomain, List<String> categories, String contentCategory, String country, String method, double confidence) {
    this.domain = domain;
    this.rootDomain = rootDomain;
    this.categories = Collections.unmodifiableList(categories);
    this.contentCategory = contentCategory;
    this.country = country;
    this.method = method;
    this.confidence = confidence;
  }

  /**
   * Splits the API's "A|B|C" field, trimming and removing duplicates.
   *
   * @param s raw field, may be null
   * @return the categories
   */
  public static List<String> splitCategories(String s) {
    List<String> out = new ArrayList<>();
    if (s == null) return out;
    Map<String, Boolean> seen = new LinkedHashMap<>();
    for (String p : s.split("\\|")) {
      String t = p.trim();
      if (!t.isEmpty() && seen.putIfAbsent(t.toLowerCase(Locale.ROOT), true) == null) out.add(t);
    }
    return out;
  }

  /**
   * Builds a classification from one decoded API record.
   *
   * @param raw decoded JSON object
   * @return the classification
   */
  public static Classification fromMap(Map<String, Object> raw) {
    Object c = raw.get("country");
    String country = c == null || PLACEHOLDERS.contains(c.toString().trim().toLowerCase(Locale.ROOT)) ? null : c.toString();
    Object conf = raw.get("confidence");
    return new Classification(
        String.valueOf(raw.getOrDefault("sni", "")),
        raw.get("root_domain") == null ? null : raw.get("root_domain").toString(),
        splitCategories(raw.get("web_filtering_category") == null ? null : raw.get("web_filtering_category").toString()),
        raw.get("category") == null ? null : raw.get("category").toString(),
        country,
        raw.get("method") == null ? "unknown" : raw.get("method").toString(),
        conf instanceof Number ? ((Number) conf).doubleValue() : 0.0);
  }

  /** @return the domain or URL that was looked up */
  public String domain() { return domain; }
  /** @return the matched root domain, or null */
  public String rootDomain() { return rootDomain; }
  /** @return web-filtering categories */
  public List<String> categories() { return categories; }
  /** @return content (IAB-style) category, or null */
  public String contentCategory() { return contentCategory; }
  /** @return country attribution, or null when unknown */
  public String country() { return country; }
  /** @return how the record was matched */
  public String method() { return method; }
  /** @return match confidence between 0 and 1 */
  public double confidence() { return confidence; }
  /** @return true if the database has categories for this domain */
  public boolean found() { return !"not_found".equals(method) && !categories.isEmpty(); }

  @Override
  public String toString() {
    return domain + " " + categories;
  }
}
