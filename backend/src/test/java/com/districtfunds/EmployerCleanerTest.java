package com.districtfunds;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.districtfunds.service.EmployerCleaner;
import com.districtfunds.service.EmployerCleaner.Bucket;
import com.districtfunds.service.EmployerCleaner.EmployerGroup;
import com.districtfunds.service.EmployerCleaner.MergeResult;
import com.districtfunds.service.EmployerCleaner.RawEmployerRow;

class EmployerCleanerTest {

    // Plain Java object, no Spring needed. That keeps these tests fast.
    private final EmployerCleaner cleaner = new EmployerCleaner();

    // ---- Buckets ----

    @ParameterizedTest
    @ValueSource(strings = {"RETIRED", "retired", "  Retired  ", "HOMEMAKER", "STUDENT",
            "UNEMPLOYED", "NOT EMPLOYED"})
    void retiredAndNotEmployedGoToTheirOwnBucket(String raw) {
        assertThat(cleaner.bucketOf(raw)).isEqualTo(Bucket.RETIRED_OR_NOT_EMPLOYED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELF", "SELF EMPLOYED", "Self-Employed", "self employed", "SELF-EMPLOYED"})
    void selfEmployedVariantsGoToTheSameBucket(String raw) {
        assertThat(cleaner.bucketOf(raw)).isEqualTo(Bucket.SELF_EMPLOYED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "NONE", "N/A", "n/a", "N.A.", "   ", "INFORMATION REQUESTED"})
    void missingEmployersGoToNotListed(String raw) {
        assertThat(cleaner.bucketOf(raw)).isEqualTo(Bucket.NOT_LISTED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void realNullAndEmptyStringsAreNotListed(String raw) {
        assertThat(cleaner.bucketOf(raw)).isEqualTo(Bucket.NOT_LISTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DCI GROUP", "U.S. SENATE", "SELF STORAGE INC", "RETIRED PILOTS ASSN"})
    void realEmployersStayEmployers(String raw) {
        // "SELF STORAGE INC" and "RETIRED PILOTS ASSN" contain bucket words but are real employers.
        assertThat(cleaner.bucketOf(raw)).isEqualTo(Bucket.EMPLOYER);
    }

    // ---- Display names (the exact examples from real FEC data) ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "DCI GROUP                  | DCI Group",
            "SRE COMPANIES              | SRE Companies",
            "U.S. SENATE                | U.S. Senate",
            "KING & SPALDING            | King & Spalding",
            "MOEHN ELECTRICAL SALES CO. | Moehn Electrical Sales",
            "BANK OF AMERICA, N.A.      | Bank of America",
            "ACME, INC.                 | Acme",
            "COCA-COLA                  | Coca-Cola",
            "AT&T                       | AT&T",
            "union pacific              | Union Pacific",
            "Union Pacific              | Union Pacific"
    })
    void displayNamesKeepAcronymsAndDropLegalSuffixes(String raw, String expected) {
        assertThat(cleaner.displayName(raw)).isEqualTo(expected);
    }

    @Test
    void displayNameIsNeverDumbTitleCase() {
        // The bug the spec warns about: plain title-casing gives "Dci Group".
        assertThat(cleaner.displayName("DCI GROUP")).isNotEqualTo("Dci Group");
    }

    // ---- Grouping keys ----

    @Test
    void spellingVariantsShareOneKey() {
        String key = cleaner.groupingKey("ACME INC.");
        assertThat(cleaner.groupingKey("Acme, Inc")).isEqualTo(key);
        assertThat(cleaner.groupingKey("  acme   llc ")).isEqualTo(key);
        assertThat(cleaner.groupingKey("ACME CORPORATION")).isEqualTo(key);
        assertThat(key).isEqualTo("acme");
    }

    @Test
    void periodsDoNotSplitAbbreviations() {
        assertThat(cleaner.groupingKey("BANK OF AMERICA, N.A."))
                .isEqualTo(cleaner.groupingKey("Bank of America"));
        assertThat(cleaner.groupingKey("U.S. SENATE")).isEqualTo("us senate");
    }

    @Test
    void nameThatIsOnlyNoiseIsKept() {
        // Don't turn "INC" into an empty string.
        assertThat(cleaner.groupingKey("INC")).isEqualTo("inc");
    }

    // ---- Merging ----

    @Test
    void mergeSumsTotalsAndCountsForTheSameEmployer() {
        MergeResult result = cleaner.merge(List.of(
                row("ACME INC.", "10.00", 1),
                row("ACME, INC", "30.25", 3),
                row("Acme Corp", "5.50", 2)));

        assertThat(result.employers()).hasSize(1);
        EmployerGroup acme = result.employers().get(0);
        assertThat(acme.total()).isEqualByComparingTo("45.75");
        assertThat(acme.count()).isEqualTo(6);
    }

    @Test
    void mergeNamesTheGroupAfterItsBiggestRawVariant() {
        MergeResult result = cleaner.merge(List.of(
                row("acme inc", "10.00", 1),
                row("ACME, INC.", "500.00", 4)));

        // "ACME, INC." has the bigger total, so its spelling wins.
        assertThat(result.employers().get(0).displayName()).isEqualTo("Acme");
    }

    @Test
    void mergeKeepsBucketsOutOfTheEmployerRanking() {
        MergeResult result = cleaner.merge(List.of(
                row("RETIRED", "9000.00", 40),
                row("SELF", "100.50", 2),
                row("SELF EMPLOYED", "50.00", 1),
                row("NULL", "20.00", 1),
                row("DCI GROUP", "300.00", 3)));

        // Retired is the biggest row, but it must not show up as an employer.
        assertThat(result.employers())
                .extracting(EmployerGroup::displayName)
                .containsExactly("DCI Group");

        assertThat(result.buckets().get(Bucket.RETIRED_OR_NOT_EMPLOYED).total())
                .isEqualByComparingTo("9000.00");
        assertThat(result.buckets().get(Bucket.SELF_EMPLOYED).total())
                .isEqualByComparingTo("150.50");
        assertThat(result.buckets().get(Bucket.SELF_EMPLOYED).count()).isEqualTo(3);
        assertThat(result.buckets().get(Bucket.NOT_LISTED).total())
                .isEqualByComparingTo("20.00");
    }

    @Test
    void mergeSortsBiggestFirstAndBreaksTiesAlphabetically() {
        MergeResult result = cleaner.merge(List.of(
                row("ZETA LLC", "100.00", 1),
                row("ALPHA LLC", "100.00", 1),
                row("BIG CO", "999.99", 1)));

        assertThat(result.employers())
                .extracting(EmployerGroup::key)
                .containsExactly("big", "alpha", "zeta");
    }

    @Test
    void mergeTreatsMissingTotalsAsZero() {
        MergeResult result = cleaner.merge(List.of(
                row("ACME", null, 1),
                row("ACME INC", "25.00", 1)));

        assertThat(result.employers().get(0).total()).isEqualByComparingTo("25.00");
    }

    @Test
    void mergeOfNothingIsEmpty() {
        MergeResult result = cleaner.merge(List.of());
        assertThat(result.employers()).isEmpty();
        assertThat(result.buckets()).isEmpty();
    }

    // Small helper so each test reads like a table of FEC rows.
    private static RawEmployerRow row(String employer, String total, long count) {
        return new RawEmployerRow(employer, total == null ? null : new BigDecimal(total), count);
    }
}