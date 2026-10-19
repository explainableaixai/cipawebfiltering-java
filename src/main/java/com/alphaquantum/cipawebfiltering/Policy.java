package com.alphaquantum.cipawebfiltering;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A set of blocked categories plus a rule for domains with several categories. Presets follow the
 * five policies of the October 2026 study of 103 million domains.
 */
public final class Policy {
  /** How a domain with several categories is treated. */
  public enum Rule {
    /** Block if any category is in the policy. */
    ANY,
    /** Block if more than half of the categories are in the policy. */
    MAJORITY,
    /** Block only if every category is in the policy. */
    ALL
  }

  /** Adult: the category that matches the content classes named in CIPA. */
  public static final List<String> STATUTORY_CORE = List.of("Adult");
  /** VPN and proxy sites. */
  public static final List<String> CIRCUMVENTION = List.of("VPN / Proxy / Anonymizers");
  /** Categories many districts add as harmful (a district choice, not a CIPA requirement). */
  public static final List<String> BROAD_HARM = List.of("Lingerie/Swimsuits/Intimate Apparel", "Violence", "Weapons", "Hate/Discrimination", "Illegal Drugs", "Gambling");
  /** Further categories of a typical district policy. */
  public static final List<String> DISTRICT_EXTRA = List.of("Alcohol", "Tobacco", "Dating", "File Sharing/P2P", "Remote Access Tools", "URL Shorteners", "Parked / For-Sale Domains", "Cryptocurrency", "Phishing / Suspicious");
  /** Categories often restricted during instruction. */
  public static final List<String> INSTRUCTIONAL = List.of("Social Networking", "Gaming", "Streaming Media", "Forums / Message Boards", "Community Sites", "Shopping", "Marketplaces / Classifieds / Auctions", "Podcasts", "Sports & Recreation", "AI / LLM Tools", "Email and Messaging", "Blogs/Personal Sites");

  private final Set<String> blocked;
  private final Rule rule;

  /**
   * @param categories blocked categories
   * @param rule multi-label rule
   */
  public Policy(Collection<String> categories, Rule rule) {
    this.blocked = Collections.unmodifiableSet(new HashSet<>(categories));
    this.rule = rule;
  }

  @SafeVarargs
  private static Policy of(List<String>... parts) {
    List<String> all = new ArrayList<>();
    for (List<String> p : parts) all.addAll(p);
    return new Policy(all, Rule.ANY);
  }

  /** @return Adult only */
  public static Policy statutoryCore() { return of(STATUTORY_CORE); }
  /** @return Adult plus VPN / proxy */
  public static Policy withCircumvention() { return of(STATUTORY_CORE, CIRCUMVENTION); }
  /** @return plus the broad harm categories */
  public static Policy broadHarm() { return of(STATUTORY_CORE, CIRCUMVENTION, BROAD_HARM); }
  /** @return a typical district policy */
  public static Policy district() { return of(STATUTORY_CORE, CIRCUMVENTION, BROAD_HARM, DISTRICT_EXTRA); }
  /** @return district policy plus instructional categories */
  public static Policy restrictive() { return of(STATUTORY_CORE, CIRCUMVENTION, BROAD_HARM, DISTRICT_EXTRA, INSTRUCTIONAL); }

  /**
   * @param r rule to use
   * @return a copy with another rule
   */
  public Policy withRule(Rule r) { return new Policy(blocked, r); }

  /**
   * @param categories categories of a domain
   * @return those this policy blocks
   */
  public List<String> matches(Collection<String> categories) {
    List<String> m = new ArrayList<>();
    for (String c : categories) if (blocked.contains(c)) m.add(c);
    return m;
  }

  /**
   * @param categories categories of a domain
   * @return true if the policy blocks the domain
   */
  public boolean blocks(Collection<String> categories) {
    int hit = matches(categories).size();
    if (hit == 0) return false;
    switch (rule) {
      case MAJORITY: return hit * 2 > categories.size();
      case ALL: return hit == categories.size();
      default: return true;
    }
  }

  /**
   * @param categories categories of a domain
   * @return true if the policy blocks the domain
   */
  public boolean blocks(String... categories) { return blocks(Arrays.asList(categories)); }
}
