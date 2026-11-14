package com.zhewu.stockcorrelation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Dependency-free Java 17 implementation of the unified replacement strategy.
 *
 * The implementation intentionally keeps the high-frequency data path separate
 * from the daily risk path:
 *
 *   daily     -> EWMA/shrunk covariance, TE candidates, tail gates, basket fit
 *   hourly    -> sample/diagnostic input; suitable for regime and execution data
 *   five-min  -> sample/diagnostic input; suitable for TCA, not replacement rank
 *
 * Example:
 *   javac -d out src/main/java/com/zhewu/stockcorrelation/StockCorrelationApp.java
 *   java -cp out com.zhewu.stockcorrelation.StockCorrelationApp \
 *     --data-dir /Users/zhewu/data/stocks/20261109 \
 *     --tickers AAPL,MSFT,GOOG
 */
public final class StockCorrelationApp {
    private static final int DEFAULT_SAMPLE_ROWS = 100;
    private static final int DEFAULT_TOP_K = 5;
    private static final int DEFAULT_MIN_OVERLAP = 252;
    private static final double DEFAULT_HALF_LIFE = 126.0;
    private static final int TRADING_DAYS_PER_YEAR = 252;
    static final int TAIL_WINDOW_DAYS = 21;
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private StockCorrelationApp() {}

    public static void main(String[] args) {
        try {
            Config config = Config.parse(args);
            if (config.help()) {
                Config.printUsage();
                return;
            }
            new Application(config).run();
        } catch (IllegalArgumentException e) {
            System.err.println("Argument error: " + e.getMessage());
            Config.printUsage();
            System.exit(2);
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            System.exit(1);
        }
    }

    enum Frequency {
        DAILY("daily", "d_us_txt.zip"),
        HOURLY("hourly", "h_us_txt.zip"),
        FIVE_MINUTE("five-minute", "5_us_txt.zip");

        private final String label;
        private final String zipName;

        Frequency(String label, String zipName) {
            this.label = label;
            this.zipName = zipName;
        }

        static Frequency fromPeriod(String period) {
            String p = period.trim().toUpperCase(Locale.ROOT);
            return switch (p) {
                case "D", "DAILY" -> DAILY;
                case "60", "H", "HOURLY" -> HOURLY;
                case "5", "5M", "5 MIN", "FIVE-MINUTE" -> FIVE_MINUTE;
                default -> throw new IllegalArgumentException("Unsupported period: " + period);
            };
        }
    }

    record PriceBar(
            String ticker,
            Frequency frequency,
            LocalDateTime timestamp,
            double open,
            double high,
            double low,
            double close,
            double volume) {}

    static final class Series {
        private final String ticker;
        private final Frequency frequency;
        private final List<PriceBar> bars;
        private final NavigableMap<LocalDateTime, Double> closes;

        Series(String ticker, Frequency frequency, List<PriceBar> bars) {
            this.ticker = ticker;
            this.frequency = frequency;
            List<PriceBar> ordered = new ArrayList<>(bars);
            ordered.sort(Comparator.comparing(PriceBar::timestamp));
            this.bars = List.copyOf(ordered);
            this.closes = new TreeMap<>();
            for (PriceBar bar : ordered) {
                if (Double.isFinite(bar.close()) && bar.close() > 0.0) {
                    this.closes.put(bar.timestamp(), bar.close());
                }
            }
        }

        String ticker() { return ticker; }
        Frequency frequency() { return frequency; }
        List<PriceBar> bars() { return bars; }
        NavigableMap<LocalDateTime, Double> closes() { return Collections.unmodifiableNavigableMap(closes); }

        /** Returns a series holding only the newest {@code maxBars} bars; 0 keeps every bar. */
        Series newest(int maxBars) {
            if (maxBars <= 0 || bars.size() <= maxBars) return this;
            return new Series(ticker, frequency, bars.subList(bars.size() - maxBars, bars.size()));
        }
    }

    record Sample(String zipName, String entryName, List<PriceBar> rows) {}

    record PairStats(
            int observations,
            double volatilityA,
            double volatilityB,
            double correlation,
            double trackingError,
            double tail95Abs21Day) {}

    record Candidate(
            String source,
            String replacement,
            int observations,
            double correlation,
            double trackingError,
            double tail95Abs21Day,
            double score) {}

    record BasketSolution(
            String source,
            Map<String, Double> weights,
            int observations,
            double residualAnnualizedVolatility,
            int iterations) {}

    static final class ZipPriceReader {
        Map<String, Series> readSeries(Path zipPath, Set<String> requested, int maxRowsPerSeries)
                throws IOException {
            Objects.requireNonNull(zipPath, "zipPath");
            if (!Files.isRegularFile(zipPath)) {
                throw new IOException("ZIP file not found: " + zipPath);
            }

            Map<String, LoadedSeries> selected = new HashMap<>();
            try (ZipFile zip = new ZipFile(zipPath.toFile())) {
                List<? extends ZipEntry> entries = zip.stream()
                        .filter(entry -> !entry.isDirectory())
                        .filter(entry -> entry.getName().toLowerCase(Locale.ROOT).endsWith(".txt"))
                        .sorted(Comparator.comparing(ZipEntry::getName))
                        .toList();

                for (ZipEntry entry : entries) {
                    String ticker = tickerOf(fileName(entry.getName()));
                    if (!requested.isEmpty() && !requested.contains(ticker)) continue;

                    int score = sourcePathScore(entry.getName());
                    LoadedSeries prior = selected.get(ticker);
                    if (prior != null && prior.sourceScore() > score) continue;

                    // Read the whole entry, then keep the newest rows after sorting by
                    // timestamp. Truncating in file order would keep the oldest history.
                    Series series = readEntry(zip, entry, ticker, 0).newest(maxRowsPerSeries);
                    if (!series.bars().isEmpty()) {
                        selected.put(ticker, new LoadedSeries(score, series));
                    }
                }
            }

            Map<String, Series> result = new LinkedHashMap<>();
            requested.stream().sorted().forEach(ticker -> {
                LoadedSeries loaded = selected.get(ticker);
                if (loaded != null) result.put(ticker, loaded.series());
            });
            return result;
        }

        Sample sampleFirstRows(Path zipPath, List<String> preferredTickers, int rows)
                throws IOException {
            if (rows <= 0) throw new IllegalArgumentException("sample rows must be positive");
            try (ZipFile zip = new ZipFile(zipPath.toFile())) {
                List<? extends ZipEntry> entries = zip.stream()
                        .filter(entry -> !entry.isDirectory())
                        .filter(entry -> entry.getName().toLowerCase(Locale.ROOT).endsWith(".txt"))
                        .sorted(Comparator.comparing(ZipEntry::getName))
                        .toList();

                ZipEntry chosen = null;
                for (String preferred : preferredTickers) {
                    String wanted = normalize(preferred);
                    for (ZipEntry entry : entries) {
                        if (tickerOf(fileName(entry.getName())).equals(wanted)) {
                            chosen = entry;
                            break;
                        }
                    }
                    if (chosen != null) break;
                }
                if (chosen == null && !entries.isEmpty()) chosen = entries.get(0);
                if (chosen == null) throw new IOException("No .txt data entries in " + zipPath);

                String ticker = tickerOf(fileName(chosen.getName()));
                Series series = readEntry(zip, chosen, ticker, rows);
                return new Sample(zipPath.getFileName().toString(), chosen.getName(), series.bars());
            }
        }

        private Series readEntry(ZipFile zip, ZipEntry entry, String ticker, int maxRows)
                throws IOException {
            List<PriceBar> bars = new ArrayList<>(maxRows > 0 ? maxRows : 4096);
            Frequency frequency = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("<TICKER>")) continue;
                    PriceBar bar;
                    try {
                        bar = parseBar(line, ticker);
                    } catch (RuntimeException malformed) {
                        // One malformed row must not discard a complete security.
                        continue;
                    }
                    if (frequency == null) frequency = bar.frequency();
                    if (bar.frequency() != frequency) continue;
                    bars.add(bar);
                    if (maxRows > 0 && bars.size() >= maxRows) break;
                }
            }
            return new Series(ticker, frequency == null ? Frequency.DAILY : frequency, bars);
        }

        private static PriceBar parseBar(String line, String fallbackTicker) {
            List<String> fields = parseCsv(line);
            if (fields.size() < 9) throw new IllegalArgumentException("Too few columns");

            String ticker = tickerOf(fields.get(0));
            if (ticker.isEmpty()) ticker = fallbackTicker;
            Frequency frequency = Frequency.fromPeriod(fields.get(1));
            LocalDate date = LocalDate.parse(fields.get(2), DATE);
            String timeText = fields.get(3).trim();
            if (timeText.length() < 6) timeText = "0".repeat(6 - timeText.length()) + timeText;
            LocalTime time = LocalTime.of(
                    Integer.parseInt(timeText.substring(0, 2)),
                    Integer.parseInt(timeText.substring(2, 4)),
                    Integer.parseInt(timeText.substring(4, 6)));

            double open = finitePositive(fields.get(4));
            double high = finitePositive(fields.get(5));
            double low = finitePositive(fields.get(6));
            double close = finitePositive(fields.get(7));
            double volume = finiteNonNegative(fields.get(8));
            return new PriceBar(ticker, frequency, LocalDateTime.of(date, time),
                    open, high, low, close, volume);
        }

        private static double finitePositive(String value) {
            double number = Double.parseDouble(value.trim());
            if (!Double.isFinite(number) || number <= 0.0) throw new IllegalArgumentException("bad price");
            return number;
        }

        private static double finiteNonNegative(String value) {
            double number = Double.parseDouble(value.trim());
            if (!Double.isFinite(number) || number < 0.0) throw new IllegalArgumentException("bad volume");
            return number;
        }

        private static List<String> parseCsv(String line) {
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (c == ',' && !quoted) {
                    fields.add(field.toString().trim());
                    field.setLength(0);
                } else {
                    field.append(c);
                }
            }
            if (quoted) throw new IllegalArgumentException("unterminated CSV quote");
            fields.add(field.toString().trim());
            return fields;
        }

        private static int sourcePathScore(String entryName) {
            String lower = entryName.toLowerCase(Locale.ROOT);
            if (lower.contains("stocks/")) return 3;
            if (lower.contains("etfs/")) return 2;
            return 1;
        }

        private static String fileName(String entryName) {
            int slash = entryName.lastIndexOf('/');
            return slash >= 0 ? entryName.substring(slash + 1) : entryName;
        }

        static String tickerOf(String fileName) {
            String value = fileName.toLowerCase(Locale.ROOT);
            if (value.endsWith(".txt")) value = value.substring(0, value.length() - 4);
            if (value.endsWith(".us")) value = value.substring(0, value.length() - 3);
            return normalize(value);
        }

        static String normalize(String ticker) {
            return ticker == null ? "" : ticker.trim().toLowerCase(Locale.ROOT).replace('.', '-');
        }

        private record LoadedSeries(int sourceScore, Series series) {}
    }

    static final class RiskEngine {
        private RiskEngine() {}

        static List<Candidate> rank(String source,
                                    Map<String, Series> universe,
                                    Set<String> blockedPairs,
                                    Map<String, String> groups,
                                    int topK,
                                    int minimumOverlap,
                                    double halfLife) {
            Series sourceSeries = universe.get(source);
            if (sourceSeries == null) return List.of();

            List<Candidate> sameGroup = new ArrayList<>();
            List<Candidate> fallback = new ArrayList<>();
            String sourceGroup = groups.getOrDefault(source, "");
            for (Map.Entry<String, Series> entry : universe.entrySet()) {
                String replacement = entry.getKey();
                if (replacement.equals(source)) continue;
                if (Compliance.blocked(source, replacement, blockedPairs)) continue;

                PairStats stats = pairStats(sourceSeries, entry.getValue(), halfLife);
                if (stats == null || stats.observations() < minimumOverlap) continue;
                // Too few aligned returns for a 21-day tail estimate: insufficient data, not a rankable score.
                if (!Double.isFinite(stats.tail95Abs21Day())) continue;
                double score = stats.trackingError()
                        + 0.25 * stats.tail95Abs21Day() * Math.sqrt((double) TRADING_DAYS_PER_YEAR / TAIL_WINDOW_DAYS);
                Candidate candidate = new Candidate(source, replacement, stats.observations(),
                        stats.correlation(), stats.trackingError(), stats.tail95Abs21Day(), score);
                if (!sourceGroup.isBlank()
                        && sourceGroup.equals(groups.getOrDefault(replacement, ""))) {
                    sameGroup.add(candidate);
                } else {
                    fallback.add(candidate);
                }
            }

            Comparator<Candidate> order = Comparator
                    .comparingDouble(Candidate::score)
                    .thenComparing(Comparator.comparingDouble(Candidate::trackingError))
                    .thenComparing(Candidate::replacement);
            sameGroup.sort(order);
            fallback.sort(order);

            List<Candidate> result = new ArrayList<>(topK);
            result.addAll(sameGroup.stream().limit(topK).toList());
            if (result.size() < topK) {
                for (Candidate candidate : fallback) {
                    if (result.stream().noneMatch(existing -> existing.replacement().equals(candidate.replacement()))) {
                        result.add(candidate);
                    }
                    if (result.size() == topK) break;
                }
            }
            return List.copyOf(result);
        }

        static PairStats pairStats(Series a, Series b, double halfLife) {
            NavigableMap<LocalDateTime, Double> returnsA = returns(a);
            NavigableMap<LocalDateTime, Double> returnsB = returns(b);
            List<Double> aValues = new ArrayList<>();
            List<Double> bValues = new ArrayList<>();
            for (LocalDateTime key : returnsA.navigableKeySet()) {
                Double right = returnsB.get(key);
                if (right != null) {
                    aValues.add(returnsA.get(key));
                    bValues.add(right);
                }
            }
            if (aValues.size() < 2) return null;

            double[] left = toArray(aValues);
            double[] right = toArray(bValues);
            WeightedMoments moments = weightedMoments(left, right, halfLife);
            double annualizer = Math.sqrt(TRADING_DAYS_PER_YEAR);
            double tail = tail95AbsDifference(left, right, TAIL_WINDOW_DAYS);
            return new PairStats(left.length, Math.sqrt(moments.varA()) * annualizer,
                    Math.sqrt(moments.varB()) * annualizer, moments.correlation(),
                    Math.sqrt(Math.max(0.0, moments.varDifference())) * annualizer, tail);
        }

        static NavigableMap<LocalDateTime, Double> returns(Series series) {
            TreeMap<LocalDateTime, Double> result = new TreeMap<>();
            Double previous = null;
            for (Map.Entry<LocalDateTime, Double> entry : series.closes().entrySet()) {
                double current = entry.getValue();
                if (previous != null && previous > 0.0 && current > 0.0) {
                    result.put(entry.getKey(), Math.log(current / previous));
                }
                previous = current;
            }
            return result;
        }

        private static WeightedMoments weightedMoments(double[] a, double[] b, double halfLife) {
            int n = a.length;
            double totalWeight = 0.0;
            double meanA = 0.0;
            double meanB = 0.0;
            for (int i = 0; i < n; i++) {
                double weight = Math.pow(0.5, (n - 1 - i) / halfLife);
                totalWeight += weight;
                meanA += weight * a[i];
                meanB += weight * b[i];
            }
            meanA /= totalWeight;
            meanB /= totalWeight;

            double varA = 0.0;
            double varB = 0.0;
            double covariance = 0.0;
            double varDifference = 0.0;
            for (int i = 0; i < n; i++) {
                double weight = Math.pow(0.5, (n - 1 - i) / halfLife);
                double da = a[i] - meanA;
                double db = b[i] - meanB;
                varA += weight * da * da;
                varB += weight * db * db;
                covariance += weight * da * db;
                double dd = da - db;
                varDifference += weight * dd * dd;
            }
            varA /= totalWeight;
            varB /= totalWeight;
            covariance /= totalWeight;
            varDifference /= totalWeight;
            double denominator = Math.sqrt(Math.max(0.0, varA * varB));
            double correlation = denominator < 1e-15 ? 0.0 : covariance / denominator;
            correlation = Math.max(-1.0, Math.min(1.0, correlation));
            return new WeightedMoments(varA, varB, covariance, varDifference, correlation);
        }

        private static double tail95AbsDifference(double[] a, double[] b, int window) {
            if (a.length < window) return Double.POSITIVE_INFINITY;
            double[] rolling = new double[a.length - window + 1];
            double sum = 0.0;
            for (int i = 0; i < a.length; i++) {
                sum += a[i] - b[i];
                if (i >= window) sum -= a[i - window] - b[i - window];
                if (i >= window - 1) rolling[i - window + 1] = Math.abs(sum);
            }
            Arrays.sort(rolling);
            return rolling[(int) Math.min(rolling.length - 1, Math.floor(0.95 * (rolling.length - 1)))];
        }

        private static double[] toArray(List<Double> values) {
            double[] result = new double[values.size()];
            for (int i = 0; i < result.length; i++) result[i] = values.get(i);
            return result;
        }

        private record WeightedMoments(double varA, double varB, double covariance,
                                       double varDifference, double correlation) {}
    }

    static final class BasketOptimizer {
        private BasketOptimizer() {}

        /**
         * Fits a long-only, fully invested basket that minimizes the EWMA return
         * difference to the source stock. This is the replacement-layer optimizer;
         * production portfolios should add index/factor constraints around it.
         */
        static BasketSolution optimize(Series source,
                                       List<Series> candidates,
                                       double halfLife,
                                       int minimumOverlap,
                                       int maxIterations) {
            if (candidates.isEmpty()) return null;
            NavigableMap<LocalDateTime, Double> sourceReturns = RiskEngine.returns(source);
            List<NavigableMap<LocalDateTime, Double>> candidateReturns = candidates.stream()
                    .map(RiskEngine::returns).toList();
            TreeSet<LocalDateTime> common = new TreeSet<>(sourceReturns.keySet());
            for (NavigableMap<LocalDateTime, Double> returns : candidateReturns) {
                common.retainAll(returns.keySet());
            }
            if (common.size() < minimumOverlap) return null;

            int n = common.size();
            int p = candidates.size();
            double[][] values = new double[n][p + 1];
            int row = 0;
            for (LocalDateTime key : common) {
                values[row][0] = sourceReturns.get(key);
                for (int j = 0; j < p; j++) values[row][j + 1] = candidateReturns.get(j).get(key);
                row++;
            }

            double[] weights = new double[n];
            double totalWeight = 0.0;
            for (int i = 0; i < n; i++) {
                weights[i] = Math.pow(0.5, (n - 1 - i) / halfLife);
                totalWeight += weights[i];
            }
            double[] means = new double[p + 1];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j <= p; j++) means[j] += weights[i] * values[i][j];
            }
            for (int j = 0; j <= p; j++) means[j] /= totalWeight;

            double[][] covariance = new double[p + 1][p + 1];
            for (int i = 0; i < n; i++) {
                for (int left = 0; left <= p; left++) {
                    double dl = values[i][left] - means[left];
                    for (int right = 0; right <= p; right++) {
                        covariance[left][right] += weights[i] * dl * (values[i][right] - means[right]);
                    }
                }
            }
            for (int left = 0; left <= p; left++) {
                for (int right = 0; right <= p; right++) covariance[left][right] /= totalWeight;
            }

            double[][] bb = new double[p][p];
            double[] cross = new double[p];
            for (int i = 0; i < p; i++) {
                cross[i] = covariance[0][i + 1];
                for (int j = 0; j < p; j++) bb[i][j] = covariance[i + 1][j + 1];
            }

            double lipschitz = 0.0;
            for (double[] rowValues : bb) {
                double sum = 0.0;
                for (double value : rowValues) sum += Math.abs(value);
                lipschitz = Math.max(lipschitz, 2.0 * sum);
            }
            double step = 0.25 / Math.max(lipschitz, 1e-12);
            double[] basketWeights = new double[p];
            Arrays.fill(basketWeights, 1.0 / p);
            int iterations = 0;
            for (; iterations < maxIterations; iterations++) {
                double[] gradient = new double[p];
                for (int i = 0; i < p; i++) {
                    double value = -2.0 * cross[i];
                    for (int j = 0; j < p; j++) value += 2.0 * bb[i][j] * basketWeights[j];
                    gradient[i] = value;
                }
                double[] next = projectSimplex(basketWeights, gradient, step);
                if (maxAbsDifference(next, basketWeights) < 1e-10) {
                    basketWeights = next;
                    break;
                }
                basketWeights = next;
            }

            double residualVariance = covariance[0][0];
            for (int i = 0; i < p; i++) {
                residualVariance -= 2.0 * basketWeights[i] * cross[i];
                for (int j = 0; j < p; j++) residualVariance += basketWeights[i] * basketWeights[j] * bb[i][j];
            }
            residualVariance = Math.max(0.0, residualVariance);
            Map<String, Double> resultWeights = new LinkedHashMap<>();
            for (int i = 0; i < p; i++) {
                if (basketWeights[i] >= 1e-5) resultWeights.put(candidates.get(i).ticker(), basketWeights[i]);
            }
            return new BasketSolution(source.ticker(), resultWeights, n,
                    Math.sqrt(residualVariance * TRADING_DAYS_PER_YEAR), iterations + 1);
        }

        private static double[] projectSimplex(double[] current, double[] gradient, double step) {
            double[] y = new double[current.length];
            for (int i = 0; i < y.length; i++) y[i] = current[i] - step * gradient[i];

            double[] sorted = y.clone();
            Arrays.sort(sorted);
            double cumulative = 0.0;
            int rho = -1;
            for (int i = sorted.length - 1; i >= 0; i--) {
                cumulative += sorted[i];
                double threshold = (cumulative - 1.0) / (sorted.length - i);
                if (sorted[i] - threshold > 0.0) {
                    rho = i;
                    break;
                }
            }
            if (rho < 0) {
                double[] equal = new double[y.length];
                Arrays.fill(equal, 1.0 / y.length);
                return equal;
            }
            double sum = 0.0;
            for (int i = sorted.length - 1; i >= rho; i--) sum += sorted[i];
            double theta = (sum - 1.0) / (sorted.length - rho);
            double[] projected = new double[y.length];
            double check = 0.0;
            for (int i = 0; i < y.length; i++) {
                projected[i] = Math.max(0.0, y[i] - theta);
                check += projected[i];
            }
            if (check > 0.0 && Math.abs(check - 1.0) > 1e-12) {
                for (int i = 0; i < projected.length; i++) projected[i] /= check;
            }
            return projected;
        }

        private static double maxAbsDifference(double[] a, double[] b) {
            double maximum = 0.0;
            for (int i = 0; i < a.length; i++) maximum = Math.max(maximum, Math.abs(a[i] - b[i]));
            return maximum;
        }
    }

    static final class Compliance {
        private Compliance() {}

        static Set<String> defaultBlockedPairs() {
            Set<String> pairs = new HashSet<>();
            add(pairs, "GOOG", "GOOGL");
            add(pairs, "FOX", "FOXA");
            add(pairs, "NWS", "NWSA");
            add(pairs, "BRK.A", "BRK.B");
            return pairs;
        }

        static void add(Set<String> pairs, String left, String right) {
            String a = ZipPriceReader.normalize(left);
            String b = ZipPriceReader.normalize(right);
            if (a.compareTo(b) < 0) pairs.add(a + "|" + b);
            else pairs.add(b + "|" + a);
        }

        static boolean blocked(String left, String right, Set<String> pairs) {
            String a = ZipPriceReader.normalize(left);
            String b = ZipPriceReader.normalize(right);
            String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
            return pairs.contains(key);
        }
    }

    /**
     * Minimal account-family ledger for integrating lot-level tax controls.
     *
     * The caller should use one accountFamily identifier for all accounts that
     * must be monitored together, including any spouse/IRA/DRIP scope required
     * by its tax policy. This class does not determine whether two instruments
     * are substantially identical; that decision belongs in Compliance data.
     *
     * The ledger consumes that Compliance data: an event on either side of a
     * related pair (for example GOOG and GOOGL) conflicts with the other ticker.
     * This is a research control only and needs professional tax review.
     */
    static final class TaxLedger {
        private static final long WINDOW_DAYS = 30L;
        private final Map<String, List<TaxEvent>> eventsByAccountFamily = new HashMap<>();
        private final Set<String> relatedPairs;

        /** Uses {@link Compliance#defaultBlockedPairs()} as the related-instrument list. */
        TaxLedger() {
            this(Compliance.defaultBlockedPairs());
        }

        TaxLedger(Set<String> relatedPairs) {
            this.relatedPairs = Set.copyOf(Objects.requireNonNull(relatedPairs, "relatedPairs"));
        }

        void recordPurchase(String accountFamily, String ticker, LocalDate date,
                            double quantity, boolean automated) {
            requireEvent(accountFamily, ticker, date, quantity);
            add(new TaxEvent(accountFamily, ZipPriceReader.normalize(ticker), date,
                    quantity, 0.0, TaxEventType.PURCHASE, automated));
        }

        void recordLossSale(String accountFamily, String ticker, LocalDate date,
                            double quantity, double lossAmount) {
            requireEvent(accountFamily, ticker, date, quantity);
            if (!Double.isFinite(lossAmount) || lossAmount <= 0.0) {
                throw new IllegalArgumentException("lossAmount must be finite and positive");
            }
            add(new TaxEvent(accountFamily, ZipPriceReader.normalize(ticker), date,
                    quantity, lossAmount, TaxEventType.LOSS_SALE, false));
        }

        /** True when a ticker can be purchased without a prior loss-sale conflict. */
        boolean canPurchase(String accountFamily, String ticker, LocalDate purchaseDate) {
            String normalized = ZipPriceReader.normalize(ticker);
            return events(accountFamily).stream().noneMatch(event ->
                    event.type() == TaxEventType.LOSS_SALE
                            && related(event.ticker(), normalized)
                            && withinPriorWindow(event.date(), purchaseDate));
        }

        /** True when a ticker can be harvested without a prior purchase conflict. */
        boolean canHarvest(String accountFamily, String ticker, LocalDate saleDate) {
            String normalized = ZipPriceReader.normalize(ticker);
            return events(accountFamily).stream().noneMatch(event ->
                    event.type() == TaxEventType.PURCHASE
                            && related(event.ticker(), normalized)
                            && withinPriorWindow(event.date(), saleDate));
        }

        List<String> activeReasons(String accountFamily, String ticker, LocalDate date) {
            String normalized = ZipPriceReader.normalize(ticker);
            List<String> reasons = new ArrayList<>();
            for (TaxEvent event : events(accountFamily)) {
                if (!related(event.ticker(), normalized) || !withinPriorWindow(event.date(), date)) continue;
                reasons.add(event.type().name().toLowerCase(Locale.ROOT)
                        + (event.ticker().equals(normalized) ? "" : " of " + event.ticker())
                        + " on " + event.date() + (event.automated() ? " (automated)" : ""));
            }
            return List.copyOf(reasons);
        }

        private boolean related(String eventTicker, String ticker) {
            return eventTicker.equals(ticker) || Compliance.blocked(eventTicker, ticker, relatedPairs);
        }

        private void add(TaxEvent event) {
            eventsByAccountFamily.computeIfAbsent(event.accountFamily(), ignored -> new ArrayList<>()).add(event);
        }

        private List<TaxEvent> events(String accountFamily) {
            return eventsByAccountFamily.getOrDefault(accountFamily, List.of());
        }

        private static boolean withinPriorWindow(LocalDate eventDate, LocalDate decisionDate) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(eventDate, decisionDate);
            return days >= 0L && days <= WINDOW_DAYS;
        }

        private static void requireEvent(String accountFamily, String ticker,
                                          LocalDate date, double quantity) {
            if (accountFamily == null || accountFamily.isBlank()) {
                throw new IllegalArgumentException("accountFamily is required");
            }
            if (ZipPriceReader.normalize(ticker).isBlank()) {
                throw new IllegalArgumentException("ticker is required");
            }
            if (date == null) throw new IllegalArgumentException("date is required");
            if (!Double.isFinite(quantity) || quantity <= 0.0) {
                throw new IllegalArgumentException("quantity must be finite and positive");
            }
        }

        private enum TaxEventType { PURCHASE, LOSS_SALE }

        private record TaxEvent(String accountFamily, String ticker, LocalDate date,
                                double quantity, double lossAmount,
                                TaxEventType type, boolean automated) {}
    }

    static final class Application {
        private final Config config;
        private final ZipPriceReader reader = new ZipPriceReader();

        Application(Config config) {
            this.config = config;
        }

        void run() throws IOException {
            Map<Frequency, Path> zips = new LinkedHashMap<>();
            for (Frequency frequency : Frequency.values()) {
                Path zip = config.dataDir().resolve(frequency.zipName);
                if (!Files.isRegularFile(zip)) throw new IOException("Missing " + frequency.label + " ZIP: " + zip);
                zips.put(frequency, zip);
            }

            System.out.println("== Input samples: first " + config.sampleRows() + " rows per ZIP ==");
            for (Map.Entry<Frequency, Path> entry : zips.entrySet()) {
                Sample sample = reader.sampleFirstRows(entry.getValue(), config.tickers(), config.sampleRows());
                printSample(entry.getKey(), sample);
            }

            System.out.println();
            System.out.println("== Daily risk analysis ==");
            Map<String, Series> daily = reader.readSeries(zips.get(Frequency.DAILY),
                    new LinkedHashSet<>(config.tickers()), config.analysisRows());
            if (daily.size() < 2) {
                System.out.println("Need at least two daily series for candidate analysis; loaded " + daily.keySet());
                return;
            }
            for (Map.Entry<String, Series> entry : daily.entrySet()) {
                System.out.printf(Locale.ROOT, "Loaded %-8s %,d daily rows%n", entry.getKey(), entry.getValue().bars().size());
            }

            Set<String> blocked = Compliance.defaultBlockedPairs();
            for (String pair : config.blockedPairs()) {
                String[] parts = pair.split("[:|,]");
                if (parts.length == 2) Compliance.add(blocked, parts[0], parts[1]);
            }

            Map<String, String> groups = new HashMap<>();
            List<Candidate> candidatesForTarget = List.of();
            String target = config.targetTicker() == null ? daily.keySet().iterator().next() : config.targetTicker();
            if (!daily.containsKey(target)) target = daily.keySet().iterator().next();

            for (String source : daily.keySet()) {
                List<Candidate> candidates = RiskEngine.rank(source, daily, blocked, groups,
                        config.topK(), config.minimumOverlap(), config.halfLife());
                if (source.equals(target)) candidatesForTarget = candidates;
                System.out.println("\nCandidates for " + source + ":");
                if (candidates.isEmpty()) {
                    System.out.println("  none passed overlap, eligibility, and risk gates");
                } else {
                    for (Candidate candidate : candidates) {
                        System.out.printf(Locale.ROOT,
                                "  %-8s overlap=%d corr=% .4f TE=% .2f%% tail21=% .2f%% score=% .2f%%%n",
                                candidate.replacement(), candidate.observations(), candidate.correlation(),
                                100.0 * candidate.trackingError(), 100.0 * candidate.tail95Abs21Day(),
                                100.0 * candidate.score());
                    }
                }
            }

            if (!candidatesForTarget.isEmpty()) {
                List<Series> basketCandidates = candidatesForTarget.stream()
                        .limit(Math.min(5, candidatesForTarget.size()))
                        .map(candidate -> daily.get(candidate.replacement()))
                        .toList();
                BasketSolution solution = BasketOptimizer.optimize(daily.get(target), basketCandidates,
                        config.halfLife(), config.minimumOverlap(), 5000);
                System.out.println("\nBasket replacement for " + target + ":");
                if (solution == null) {
                    System.out.println("  unavailable: not enough common observations across the basket");
                } else {
                    System.out.printf(Locale.ROOT, "  overlap=%d residual annualized volatility=% .2f%% iterations=%d%n",
                            solution.observations(), 100.0 * solution.residualAnnualizedVolatility(), solution.iterations());
                    for (Map.Entry<String, Double> weight : solution.weights().entrySet()) {
                        System.out.printf(Locale.ROOT, "  %-8s weight=% .2f%%%n", weight.getKey(), 100.0 * weight.getValue());
                    }
                }
            }

            System.out.println("\nNo tax-ledger or wash-sale check was run; candidates are statistical research output only.");
            System.out.println("Intraday data was sampled for input validation only; hourly and five-minute series are not used as core replacement signals.");
        }

        private static void printSample(Frequency frequency, Sample sample) {
            List<PriceBar> rows = sample.rows();
            System.out.printf(Locale.ROOT, "%s: %s entry=%s rows=%d%n", frequency.label,
                    sample.zipName(), sample.entryName(), rows.size());
            if (!rows.isEmpty()) {
                PriceBar first = rows.get(0);
                PriceBar last = rows.get(rows.size() - 1);
                System.out.printf(Locale.ROOT, "  first=%s close=% .6f, last=%s close=% .6f%n",
                        first.timestamp(), first.close(), last.timestamp(), last.close());
            }
        }
    }

    record Config(
            Path dataDir,
            List<String> tickers,
            String targetTicker,
            int sampleRows,
            int analysisRows,
            int topK,
            int minimumOverlap,
            double halfLife,
            List<String> blockedPairs,
            boolean help) {

        static Config parse(String[] args) {
            Path defaultData = Path.of(System.getProperty("user.home"), "data", "stocks", "20261109");
            Path dataDir = defaultData;
            List<String> tickers = new ArrayList<>(List.of("aapl", "msft", "goog"));
            String target = null;
            int sampleRows = DEFAULT_SAMPLE_ROWS;
            int analysisRows = 0;
            int topK = DEFAULT_TOP_K;
            int minOverlap = DEFAULT_MIN_OVERLAP;
            double halfLife = DEFAULT_HALF_LIFE;
            List<String> blockedPairs = new ArrayList<>();
            boolean help = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-h", "--help" -> help = true;
                    case "--data-dir" -> dataDir = Path.of(requireValue(args, ++i, arg));
                    case "--tickers" -> tickers = parseTickers(requireValue(args, ++i, arg));
                    case "--target" -> target = ZipPriceReader.normalize(requireValue(args, ++i, arg));
                    case "--sample-rows" -> sampleRows = positiveInt(requireValue(args, ++i, arg), arg);
                    case "--analysis-rows" -> analysisRows = nonNegativeInt(requireValue(args, ++i, arg), arg);
                    case "--top-k" -> topK = positiveInt(requireValue(args, ++i, arg), arg);
                    case "--min-overlap" -> minOverlap = positiveInt(requireValue(args, ++i, arg), arg);
                    case "--half-life" -> halfLife = positiveDouble(requireValue(args, ++i, arg), arg);
                    case "--blocked-pair" -> blockedPairs.add(requireValue(args, ++i, arg));
                    default -> throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }
            if (tickers.size() < 2) throw new IllegalArgumentException("at least two tickers are required");
            if (sampleRows <= 0 || topK <= 0 || minOverlap <= 1 || halfLife <= 0.0) {
                throw new IllegalArgumentException("invalid positive configuration");
            }
            if (minOverlap < TAIL_WINDOW_DAYS) {
                throw new IllegalArgumentException("--min-overlap must be at least "
                        + TAIL_WINDOW_DAYS + " for the tail-divergence check");
            }
            return new Config(dataDir.toAbsolutePath().normalize(), tickers, target, sampleRows,
                    analysisRows, topK, minOverlap, halfLife, List.copyOf(blockedPairs), help);
        }

        private static List<String> parseTickers(String text) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (String token : text.split("[,\\s]+")) {
                String normalized = ZipPriceReader.normalize(token);
                if (!normalized.isEmpty()) result.add(normalized);
            }
            return new ArrayList<>(result);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) throw new IllegalArgumentException(option + " requires a value");
            return args[index];
        }

        private static int positiveInt(String text, String option) {
            int value = Integer.parseInt(text);
            if (value <= 0) throw new IllegalArgumentException(option + " must be positive");
            return value;
        }

        private static int nonNegativeInt(String text, String option) {
            int value = Integer.parseInt(text);
            if (value < 0) throw new IllegalArgumentException(option + " must be non-negative");
            return value;
        }

        private static double positiveDouble(String text, String option) {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value) || value <= 0.0) throw new IllegalArgumentException(option + " must be positive");
            return value;
        }

        static void printUsage() {
            System.err.println("""
                    Usage:
                      javac -d out src/main/java/com/zhewu/stockcorrelation/StockCorrelationApp.java
                      java -cp out com.zhewu.stockcorrelation.StockCorrelationApp [options]

                    Options:
                      --data-dir <dir>       Data directory (default: ~/data/stocks/20261109)
                      --tickers <list>       Comma/space-separated tickers (default: AAPL,MSFT,GOOG)
                      --target <ticker>      Basket report target (default: first loaded ticker)
                      --sample-rows <n>      Rows sampled from each ZIP (default: 100)
                      --analysis-rows <n>    Newest daily rows used; 0 means all history (default: 0)
                      --top-k <n>             Candidates per stock (default: 5)
                      --min-overlap <n>       Minimum shared returns, at least 21 (default: 252)
                      --half-life <days>      EWMA half-life (default: 126)
                      --blocked-pair A:B      Additional issuer/compliance exclusion; repeatable
                      -h, --help              Show this help
                    """);
        }
    }
}
