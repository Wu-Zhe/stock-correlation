package com.zhewu.stockcorrelation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StockCorrelationAppTest {
    private static final String HEADER =
            "<TICKER>,<PER>,<DATE>,<TIME>,<OPEN>,<HIGH>,<LOW>,<CLOSE>,<VOL>,<OPENINT>\n";

    @TempDir
    Path tempDir;

    @Test
    void frequencyAndTickerNormalizationAreStrictAndStable() {
        assertEquals(StockCorrelationApp.Frequency.DAILY,
                StockCorrelationApp.Frequency.fromPeriod("D"));
        assertEquals(StockCorrelationApp.Frequency.HOURLY,
                StockCorrelationApp.Frequency.fromPeriod("60"));
        assertEquals(StockCorrelationApp.Frequency.FIVE_MINUTE,
                StockCorrelationApp.Frequency.fromPeriod("5M"));
        assertThrows(IllegalArgumentException.class,
                () -> StockCorrelationApp.Frequency.fromPeriod("tick"));

        assertEquals("brk-b", StockCorrelationApp.ZipPriceReader.normalize(" BRK.B "));
        assertEquals("aapl", StockCorrelationApp.ZipPriceReader.tickerOf("AAPL.US.txt"));
        assertEquals("aapl", StockCorrelationApp.ZipPriceReader.tickerOf("aapl"));
        assertEquals("", StockCorrelationApp.ZipPriceReader.normalize(null));
    }

    @Test
    void zipReaderSamplesRowsSkipsMalformedRowsAndPrefersStockEntries() throws Exception {
        Path zip = writeZip(Map.of(
                "data/daily/us/nasdaq etfs/1/aapl.us.txt", dailyRows("AAPL.US", 4, 100.0),
                "data/daily/us/nasdaq stocks/1/aapl.us.txt", dailyRows("AAPL.US", 6, 100.0)
                        + "AAPL.US,X,20240120,000000,1,1,1,1,1,0\n"
                        + "AAPL.US,D,20240121,000000,bad,1,1,1,1,0\n",
                "data/daily/us/z-other/aapl.us.txt", dailyRows("AAPL.US", 2, 100.0),
                "data/daily/us/nasdaq stocks/1/msft.us.txt", dailyRows("MSFT.US", 6, 200.0)));

        StockCorrelationApp.ZipPriceReader reader = new StockCorrelationApp.ZipPriceReader();
        StockCorrelationApp.Sample sample = reader.sampleFirstRows(zip, List.of("AAPL"), 3);
        assertEquals(3, sample.rows().size());
        assertTrue(sample.entryName().contains("aapl.us.txt"));

        Map<String, StockCorrelationApp.Series> loaded = reader.readSeries(
                zip, new LinkedHashSet<>(List.of("aapl", "msft")), 0);
        assertEquals(Set.of("aapl", "msft"), loaded.keySet());
        assertEquals(6, loaded.get("aapl").bars().size());
        assertEquals(6, loaded.get("msft").bars().size());

        Map<String, StockCorrelationApp.Series> all = reader.readSeries(zip, Set.of(), 2);
        assertTrue(all.isEmpty());
        assertThrows(IOException.class, () -> reader.sampleFirstRows(tempDir.resolve("missing.zip"), List.of("AAPL"), 3));
        assertThrows(IOException.class, () -> reader.readSeries(tempDir.resolve("missing.zip"), Set.of("aapl"), 0));
    }

    @Test
    void zipReaderCanUseFallbackEntryAndHandlesQuotedFields() throws Exception {
        String quoted = "\"AAPL.US\",D,20240101,000000,\"100\",101,99,100,0,0\n";
        Path zip = writeZip(Map.of("data/daily/us/nasdaq stocks/1/aapl.us.txt", HEADER + quoted));
        StockCorrelationApp.ZipPriceReader reader = new StockCorrelationApp.ZipPriceReader();
        StockCorrelationApp.Sample sample = reader.sampleFirstRows(zip, List.of("NOT_PRESENT"), 100);
        assertEquals(1, sample.rows().size());
        assertEquals(100.0, sample.rows().get(0).close(), 1e-9);
    }

    @Test
    void pairStatisticsUseAlignedReturnsAndTailRisk() {
        StockCorrelationApp.Series source = syntheticSeries("aapl", 320, 100.0, 0.006, 0.0002);
        StockCorrelationApp.Series peer = syntheticSeries("msft", 320, 200.0, 0.006, 0.0002);
        StockCorrelationApp.PairStats stats = StockCorrelationApp.RiskEngine.pairStats(source, peer, 126.0);

        assertNotNull(stats);
        assertEquals(319, stats.observations());
        assertTrue(stats.correlation() > 0.99);
        assertTrue(stats.trackingError() >= 0.0);
        assertTrue(stats.tail95Abs21Day() >= 0.0);
        assertEquals(319, StockCorrelationApp.RiskEngine.returns(source).size());

        StockCorrelationApp.Series oneRow = new StockCorrelationApp.Series("one", StockCorrelationApp.Frequency.DAILY,
                List.of(bar("one", 1, 100.0)));
        assertNull(StockCorrelationApp.RiskEngine.pairStats(oneRow, oneRow, 126.0));
    }

    @Test
    void candidateRankingUsesTEGroupsAndComplianceExclusions() {
        Map<String, StockCorrelationApp.Series> universe = new LinkedHashMap<>();
        universe.put("aapl", syntheticSeries("aapl", 320, 100.0, 0.006, 0.0));
        universe.put("msft", syntheticSeries("msft", 320, 200.0, 0.006, 0.0002));
        universe.put("goog", syntheticSeries("goog", 320, 300.0, 0.004, 0.001));

        Set<String> blocked = StockCorrelationApp.Compliance.defaultBlockedPairs();
        StockCorrelationApp.Compliance.add(blocked, "AAPL", "MSFT");
        Map<String, String> groups = Map.of("aapl", "tech", "msft", "tech", "goog", "finance");

        List<StockCorrelationApp.Candidate> candidates = StockCorrelationApp.RiskEngine.rank(
                "aapl", universe, blocked, groups, 2, 250, 126.0);
        assertEquals(1, candidates.size());
        assertEquals("goog", candidates.get(0).replacement());
        assertTrue(StockCorrelationApp.RiskEngine.rank("missing", universe, blocked, groups, 2, 250, 126.0).isEmpty());

        assertTrue(StockCorrelationApp.Compliance.blocked("GOOG", "GOOGL", blocked));
        assertFalse(StockCorrelationApp.Compliance.blocked("AAPL", "GOOG", blocked));
    }

    @Test
    void basketOptimizerFitsLongOnlyWeightsAndRejectsInsufficientOverlap() {
        StockCorrelationApp.Series source = syntheticSeries("aapl", 320, 100.0, 0.006, 0.0);
        StockCorrelationApp.Series peerOne = syntheticSeries("msft", 320, 200.0, 0.006, 0.0002);
        StockCorrelationApp.Series peerTwo = syntheticSeries("goog", 320, 300.0, 0.004, 0.001);

        StockCorrelationApp.BasketSolution solution = StockCorrelationApp.BasketOptimizer.optimize(
                source, List.of(peerOne, peerTwo), 126.0, 250, 5000);
        assertNotNull(solution);
        assertEquals(319, solution.observations());
        assertTrue(solution.residualAnnualizedVolatility() >= 0.0);
        assertTrue(solution.iterations() > 0);
        assertEquals(1.0, solution.weights().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-8);
        assertTrue(solution.weights().values().stream().allMatch(weight -> weight >= 0.0));

        assertNull(StockCorrelationApp.BasketOptimizer.optimize(source, List.of(), 126.0, 250, 5000));
        assertNull(StockCorrelationApp.BasketOptimizer.optimize(source, List.of(peerOne), 126.0, 1000, 5000));
    }

    @Test
    void taxLedgerTracksPriorPurchasesLossesAndAutomatedActivity() {
        StockCorrelationApp.TaxLedger ledger = new StockCorrelationApp.TaxLedger();
        LocalDate date = LocalDate.of(2026, 1, 10);

        ledger.recordPurchase("family", "MSFT", date, 2.0, true);
        assertFalse(ledger.canHarvest("family", "MSFT", date.plusDays(30)));
        assertTrue(ledger.canHarvest("family", "MSFT", date.plusDays(31)));
        assertTrue(ledger.activeReasons("family", "MSFT", date.plusDays(1)).get(0).contains("automated"));

        ledger.recordLossSale("family", "AAPL", date, 1.0, 100.0);
        assertFalse(ledger.canPurchase("family", "AAPL", date.plusDays(30)));
        assertTrue(ledger.canPurchase("family", "AAPL", date.plusDays(31)));

        assertThrows(IllegalArgumentException.class,
                () -> ledger.recordPurchase("", "AAPL", date, 1.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.recordPurchase("family", "", date, 1.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.recordPurchase("family", "AAPL", null, 1.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.recordPurchase("family", "AAPL", date, 0.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.recordLossSale("family", "AAPL", date, 1.0, 0.0));
    }

    @Test
    void configParsesOptionsAndRejectsInvalidValues() {
        StockCorrelationApp.Config config = StockCorrelationApp.Config.parse(new String[] {
                "--data-dir", tempDir.toString(),
                "--tickers", "AAPL,MSFT GOOG",
                "--target", "AAPL",
                "--sample-rows", "3",
                "--analysis-rows", "10",
                "--top-k", "2",
                "--min-overlap", "5",
                "--half-life", "7",
                "--blocked-pair", "AAPL:MSFT"
        });
        assertEquals(List.of("aapl", "msft", "goog"), config.tickers());
        assertEquals("aapl", config.targetTicker());
        assertEquals(3, config.sampleRows());
        assertEquals(10, config.analysisRows());
        assertEquals(2, config.topK());
        assertEquals(5, config.minimumOverlap());
        assertEquals(7.0, config.halfLife(), 1e-9);
        assertEquals(List.of("AAPL:MSFT"), config.blockedPairs());

        assertTrue(StockCorrelationApp.Config.parse(new String[] {"--help"}).help());
        StockCorrelationApp.Config.printUsage();
        assertThrows(IllegalArgumentException.class,
                () -> StockCorrelationApp.Config.parse(new String[] {"--unknown"}));
        assertThrows(IllegalArgumentException.class,
                () -> StockCorrelationApp.Config.parse(new String[] {"--tickers", "AAPL"}));
        assertThrows(IllegalArgumentException.class,
                () -> StockCorrelationApp.Config.parse(new String[] {"--sample-rows", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> StockCorrelationApp.Config.parse(new String[] {"--analysis-rows", "-1"}));
        assertThrows(IllegalArgumentException.class,
                () -> StockCorrelationApp.Config.parse(new String[] {"--top-k", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> StockCorrelationApp.Config.parse(new String[] {"--min-overlap", "1"}));
        assertThrows(IllegalArgumentException.class,
                () -> StockCorrelationApp.Config.parse(new String[] {"--half-life", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> StockCorrelationApp.Config.parse(new String[] {"--data-dir"}));
    }

    @Test
    void applicationRunsAgainstThreeZipInputsAndSamplesEachFrequency() throws Exception {
        String hourly = rows("AAPL.US", "60", 5, 100.0, LocalDate.of(2026, 1, 1), 60);
        String fiveMinute = rows("AAPL.US", "5", 5, 100.0, LocalDate.of(2026, 1, 1), 5);
        writeZip(tempDir.resolve("d_us_txt.zip"), Map.of(
                "data/daily/us/nasdaq stocks/1/aapl.us.txt", dailyRows("AAPL.US", 32, 100.0),
                "data/daily/us/nasdaq stocks/1/msft.us.txt", dailyRows("MSFT.US", 32, 200.0)));
        writeZip(tempDir.resolve("h_us_txt.zip"), Map.of(
                "data/hourly/us/nasdaq stocks/1/aapl.us.txt", hourly));
        writeZip(tempDir.resolve("5_us_txt.zip"), Map.of(
                "data/5 min/us/nasdaq stocks/1/aapl.us.txt", fiveMinute));

        StockCorrelationApp.Config config = new StockCorrelationApp.Config(
                tempDir, List.of("aapl", "msft"), "aapl", 3, 0, 1, 5, 10.0, List.of(), false);
        new StockCorrelationApp.Application(config).run();
    }

    @Test
    void mainHelpPathIsCallable() {
        StockCorrelationApp.main(new String[] {"--help"});
    }

    private Path writeZip(Map<String, String> entries) throws IOException {
        Path zip = tempDir.resolve("input-" + System.nanoTime() + ".zip");
        return writeZip(zip, entries);
    }

    private static Path writeZip(Path zip, Map<String, String> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return zip;
    }

    private static String dailyRows(String ticker, int count, double start) {
        return rows(ticker, "D", count, start, LocalDate.of(2024, 1, 1), 1);
    }

    private static String rows(String ticker, String period, int count, double start,
                               LocalDate startDate, int minutesPerRow) {
        StringBuilder result = new StringBuilder(HEADER);
        for (int i = 0; i < count; i++) {
            LocalDate date = startDate.plusDays(i);
            LocalTime time = period.equals("D") ? LocalTime.MIDNIGHT : LocalTime.of(9, 30).plusMinutes((long) i * minutesPerRow);
            double close = start + i * 0.5 + Math.sin(i * 0.13);
            result.append(ticker).append(',').append(period).append(',')
                    .append(date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)).append(',')
                    .append(String.format("%02d%02d%02d", time.getHour(), time.getMinute(), time.getSecond()))
                    .append(',').append(close).append(',').append(close + 1.0).append(',')
                    .append(close - 1.0).append(',').append(close).append(',').append(1000 + i).append(",0\n");
        }
        return result.toString();
    }

    private static StockCorrelationApp.PriceBar bar(String ticker, int day, double close) {
        LocalDateTime timestamp = LocalDateTime.of(LocalDate.of(2024, 1, 1).plusDays(day), LocalTime.MIDNIGHT);
        return new StockCorrelationApp.PriceBar(ticker, StockCorrelationApp.Frequency.DAILY,
                timestamp, close, close + 1.0, close - 1.0, close, 1000.0);
    }

    private static StockCorrelationApp.Series syntheticSeries(String ticker, int count,
                                                               double start, double drift, double noise) {
        List<StockCorrelationApp.PriceBar> bars = new ArrayList<>();
        double price = start;
        for (int i = 0; i < count; i++) {
            price *= Math.exp(drift + noise * Math.sin(i * 0.11));
            bars.add(bar(ticker, i, price));
        }
        return new StockCorrelationApp.Series(ticker, StockCorrelationApp.Frequency.DAILY, bars);
    }
}
