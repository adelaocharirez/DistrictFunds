package com.districtfunds.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
* cleans up self reported employees 
*/

@Component
public class EmployerCleaner {
	public enum Bucket {
		EMPLOYER("Employer"),
		RETIRED_OR_NOT_EMPLOYED("Retired/ not employed"),
		SELF_EMPLOYED("Self-employed"),
		NOT_LISTED("No employer listed");

		private final String label;

		Bucket(String label) {
			this.label = label;
		}

		public String label() {		

			return label;
		}
	}


	public record RawEmployerRow(String employer, BigDecimal total, long count){}

	public record EmployerGroup(String key, String displayName, BigDecimal total,long count ) {}
	public record MergeResult(List<EmployerGroup> employers, Map<Bucket, EmployerGroup> buckets) {}


	private static final Set<String> RETIRED_OR_NOT_EMPLOYED = Set.of(
        "retired", "retiree", "homemaker", "home maker", "unemployed",
        "not employed", "not currently employed", "student", "disabled");

	private static final Set<String> SELF_EMPLOYED = Set.of(
        "self", "self employed", "selfemployed", "self employeed", "self emp");


	private static final Set<String> NOT_LISTED = Set.of(
            "null", "none", "n a", "na", "unknown", "not applicable",
            "information requested", "information requested per best efforts",
            "requested", "requested per best efforts");
 
    // Legal suffixes and filler that shouldn't split one employer into two.
    private static final Set<String> NOISE_WORDS = Set.of(
            "inc", "llc", "llp", "co", "corp", "corporation", "company", "na", "ltd");
 
    // Small words that read better lowercase in a name ("Bank of America").
    private static final Set<String> LOWERCASE_WORDS = Set.of(
            "of", "and", "the", "for", "at", "in", "on", "a", "an", "to");


	/** Decides which bucket a raw employer string belongs in. */
    public Bucket bucketOf(String raw) {
        String basic = basicNormalize(raw);
        if (basic.isEmpty() || NOT_LISTED.contains(basic)) {
            return Bucket.NOT_LISTED;
        }
        if (RETIRED_OR_NOT_EMPLOYED.contains(basic)) {
            return Bucket.RETIRED_OR_NOT_EMPLOYED;
        }
        if (SELF_EMPLOYED.contains(basic)) {
            return Bucket.SELF_EMPLOYED;
        }
        return Bucket.EMPLOYER;
    }
 
    /**
     * The grouping key: lowercase, no punctuation, no legal suffixes.
     * "Moehn Electrical Sales Co." and "MOEHN ELECTRICAL SALES" both become
     * "moehn electrical sales". Used for grouping only, never shown to users.
     */
    public String groupingKey(String raw) {
        String basic = basicNormalize(raw);
        List<String> kept = new ArrayList<>();
        for (String word : basic.split(" ")) {
            if (!word.isEmpty() && !NOISE_WORDS.contains(word)) {
                kept.add(word);
            }
        }
        // If the whole name was noise (say, just "INC"), keep what we had.
        return kept.isEmpty() ? basic : String.join(" ", kept);
    }
 
    /**
     * A readable name built from the raw string, without blindly title-casing it.
     * "DCI GROUP" stays "DCI Group" (not "Dci Group"), "U.S. SENATE" becomes
     * "U.S. Senate", and "MOEHN ELECTRICAL SALES CO." becomes "Moehn Electrical Sales".
     */
    public String displayName(String raw) {
        if (raw == null || raw.isBlank()) {
            return Bucket.NOT_LISTED.label();
        }
        String trimmed = raw.trim().replaceAll("\\s+", " ");
 
        // Mixed case means the donor typed the casing on purpose, so trust it.
        boolean mixedCase = !trimmed.equals(trimmed.toUpperCase(Locale.ROOT))
                && !trimmed.equals(trimmed.toLowerCase(Locale.ROOT));
 
        List<String> words = new ArrayList<>();
        for (String word : trimmed.split(" ")) {
            String bare = word.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
            if (NOISE_WORDS.contains(bare)) {
                continue; // drop "INC.", "LLC", "CO." and friends
            }
            words.add(mixedCase ? word : formatWord(word.toUpperCase(Locale.ROOT), words.isEmpty()));
        }
 
        String result = String.join(" ", words).replaceAll("[\\s,&-]+$", "");
        return result.isEmpty() ? trimmed : result;
    }
 
    /**
     * Cleans every row, groups the ones with the same key, and adds up their totals.
     * The display name for a group comes from its biggest raw variant.
     */
    public MergeResult merge(List<RawEmployerRow> rows) {
        Map<String, BigDecimal> totals = new HashMap<>();
        Map<String, Long> counts = new HashMap<>();
        Map<String, String> biggestRawName = new HashMap<>();
        Map<String, BigDecimal> biggestRawTotal = new HashMap<>();
 
        Map<Bucket, BigDecimal> bucketTotals = new EnumMap<>(Bucket.class);
        Map<Bucket, Long> bucketCounts = new EnumMap<>(Bucket.class);
 
        for (RawEmployerRow row : rows) {
            BigDecimal amount = row.total() == null ? BigDecimal.ZERO : row.total();
            Bucket bucket = bucketOf(row.employer());
 
            if (bucket != Bucket.EMPLOYER) {
                bucketTotals.merge(bucket, amount, BigDecimal::add);
                bucketCounts.merge(bucket, row.count(), Long::sum);
                continue;
            }
 
            String key = groupingKey(row.employer());
            totals.merge(key, amount, BigDecimal::add);
            counts.merge(key, row.count(), Long::sum);
 
            BigDecimal currentBiggest = biggestRawTotal.get(key);
            if (currentBiggest == null || amount.compareTo(currentBiggest) > 0) {
                biggestRawTotal.put(key, amount);
                biggestRawName.put(key, row.employer());
            }
        }
 
        List<EmployerGroup> employers = new ArrayList<>();
        for (String key : totals.keySet()) {
            employers.add(new EmployerGroup(
                    key, displayName(biggestRawName.get(key)), totals.get(key), counts.get(key)));
        }
        // Biggest first; ties broken alphabetically so the order is always the same.
        employers.sort(Comparator.comparing(EmployerGroup::total).reversed()
                .thenComparing(EmployerGroup::key));
 
        Map<Bucket, EmployerGroup> buckets = new EnumMap<>(Bucket.class);
        for (Bucket bucket : bucketTotals.keySet()) {
            buckets.put(bucket, new EmployerGroup(
                    bucket.name(), bucket.label(), bucketTotals.get(bucket), bucketCounts.get(bucket)));
        }
 
        return new MergeResult(employers, buckets);
    }
 
    // ---- helpers ----
 
    /**
     * Steps 1 and 2: lowercase, trim, punctuation to spaces, collapse spaces.
     * Periods are removed instead of spaced out, so "N.A." becomes "na" and
     * "U.S." becomes "us" rather than splitting into separate letters.
     */
    private String basicNormalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replace(".", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
 
    /** Formats one ALL-CAPS word for display. */
    private String formatWord(String word, boolean isFirst) {
        int letters = word.replaceAll("[^A-Z]", "").length();
 
        // Short words and anything with a period are probably acronyms: DCI, SRE, U.S.
        if (word.contains(".") || letters <= 3) {
            String lower = word.toLowerCase(Locale.ROOT);
            if (!isFirst && LOWERCASE_WORDS.contains(lower)) {
                return lower; // "of", "and", "the" in the middle of a name
            }
            if (isFirst && LOWERCASE_WORDS.contains(lower)) {
                return capitalize(lower); // "The ..." at the start
            }
            return word;
        }
 
        // Capitalize each part of hyphenated words too: "COCA-COLA" -> "Coca-Cola".
        String[] parts = word.toLowerCase(Locale.ROOT).split("-", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = capitalize(parts[i]);
        }
        return String.join("-", parts);
    }
 
    private String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
	
