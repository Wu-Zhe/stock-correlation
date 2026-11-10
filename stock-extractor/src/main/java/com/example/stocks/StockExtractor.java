package com.example.stocks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts only the entries of a historical-price zip whose file names match
 * the requested stock tickers. Nothing else is ever written to disk.
 *
 * Usage:
 *   java -jar stock-extractor.jar -z d_us_txt.zip [-o outDir] [-p] AAPL MSFT,TSLA ...
 *
 * A zip entry matches ticker T when its file name (last path segment) starts
 * with T followed by '.', e.g. "data/daily/us/nasdaq etfs/aaxj.us.txt" -> "aaxj".
 * Matching is case-insensitive and exact, so "abi" does not match "abig.us.txt".
 */
public final class StockExtractor {

    private StockExtractor() {}

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            code = 2;
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            code = 1;
        }
        System.exit(code);
    }

    static int run(String[] args) throws IOException {
        Path zipPath = null;
        Path outDir = Paths.get("output");
        boolean preservePaths = false;
        Set<String> tickers = new LinkedHashSet<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> {
                    printUsage();
                    return 0;
                }
                case "-z", "--zip" -> zipPath = Paths.get(requireValue(args, ++i, a));
                case "-o", "--out" -> outDir = Paths.get(requireValue(args, ++i, a));
                case "-p", "--preserve-paths" -> preservePaths = true;
                default -> {
                    if (a.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown option: " + a);
                    }
                    // Accept "AAPL", "AAPL,MSFT" or any mix.
                    Arrays.stream(a.split("[,\\s]+"))
                            .map(s -> s.trim().toLowerCase(Locale.ROOT))
                            .filter(s -> !s.isEmpty())
                            .forEach(tickers::add);
                }
            }
        }

        if (zipPath == null) throw new IllegalArgumentException("Missing required option --zip");
        if (tickers.isEmpty()) throw new IllegalArgumentException("Provide at least one ticker");
        if (!Files.isRegularFile(zipPath)) {
            throw new IllegalArgumentException("Zip file not found: " + zipPath);
        }

        List<Path> written = extract(zipPath, outDir, tickers, preservePaths);

        Set<String> found = new HashSet<>();
        for (Path p : written) found.add(tickerOf(p.getFileName().toString()));
        List<String> missing = new ArrayList<>();
        for (String t : tickers) if (!found.contains(t)) missing.add(t);

        System.out.printf("Extracted %d file(s) to %s%n", written.size(), outDir.toAbsolutePath());
        if (!missing.isEmpty()) {
            System.err.println("No match found for: " + String.join(", ", missing));
        }
        return written.isEmpty() ? 1 : 0;
    }

    /**
     * Streams matching entries straight from the zip to disk.
     *
     * ZipFile reads the zip's central directory (the index at the end of the
     * file), so entries that don't match are never decompressed - only the
     * requested tickers cost any inflate time or disk space.
     */
    static List<Path> extract(Path zipPath, Path outDir, Set<String> tickers, boolean preservePaths)
            throws IOException {
        Path root = outDir.toAbsolutePath().normalize();
        Files.createDirectories(root);

        List<Path> written = new ArrayList<>();
        Set<Path> usedTargets = new HashSet<>();

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String entryName = entry.getName();
                String baseName = entryName.substring(entryName.lastIndexOf('/') + 1);
                if (!tickers.contains(tickerOf(baseName))) continue;

                Path target = resolveTarget(root, entryName, baseName, preservePaths, usedTargets);
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                written.add(target);
                System.out.println("  " + entryName + " -> " + root.relativize(target));
            }
        }
        return written;
    }

    private static Path resolveTarget(Path root, String entryName, String baseName,
                                      boolean preservePaths, Set<Path> usedTargets) {
        String relative = preservePaths ? entryName : baseName;
        Path target = root.resolve(relative).normalize();

        // Flattened output can collide (same ticker in two folders); disambiguate
        // by prefixing the parent folder name.
        if (!preservePaths && usedTargets.contains(target)) {
            String[] parts = entryName.split("/");
            String parent = parts.length >= 2 ? parts[parts.length - 2] : "dup";
            String safeParent = parent.replaceAll("[^A-Za-z0-9._-]", "_");
            target = root.resolve(safeParent + "_" + baseName).normalize();
        }

        // Zip-slip guard: never write outside the output directory.
        if (!target.startsWith(root)) {
            throw new SecurityException("Blocked entry escaping output dir: " + entryName);
        }
        usedTargets.add(target);
        return target;
    }

    /** "aaxj.us.txt" -> "aaxj" (lower-cased). */
    static String tickerOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.indexOf('.');
        return dot < 0 ? lower : lower.substring(0, dot);
    }

    private static String requireValue(String[] args, int idx, String opt) {
        if (idx >= args.length) throw new IllegalArgumentException("Option " + opt + " needs a value");
        return args[idx];
    }

    private static void printUsage() {
        System.err.println("""
                Usage: java -jar stock-extractor.jar -z <file.zip> [-o <outDir>] [-p] <TICKER>...

                  -z, --zip <file>        Path to the zip (e.g. d_us_txt.zip)   [required]
                  -o, --out <dir>         Output directory (default: ./output)
                  -p, --preserve-paths    Keep the folder structure from the zip (default: flat)
                  -h, --help              Show this help

                Tickers may be space- or comma-separated, case-insensitive.
                Example: java -jar stock-extractor.jar -z d_us_txt.zip -o out AAXJ AADR,aaus
                """);
    }
}
