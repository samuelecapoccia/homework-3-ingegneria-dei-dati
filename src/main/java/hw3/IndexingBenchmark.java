package hw3;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Misura i tempi di indicizzazione al variare del numero di documenti e dell'analyzer.
 *
 * Per ogni (analyzer, numero di documenti) esegue N run su un indice temporaneo, sempre
 * ricreato da zero, e ne riporta la mediana. Una prima esecuzione completa di riscaldamento
 * (scartata) carica le classi, attiva il JIT e porta i file nella cache del sistema operativo.
 *
 * Uso: bin/benchmark.sh [--docs data/docs] [--runs 5] [--sizes 5000,10000,15000,20000,0]
 *                      [--analyzers english,standard,whitespace]
 * (0 = tutti i documenti). Output: results/indexing_runs.csv, results/indexing_summary.csv
 */
public final class IndexingBenchmark {

    public static void main(String[] args) throws IOException {
        Path docs = Paths.get("data/docs");
        int runs = 5;
        int[] sizes = {5000, 10000, 15000, 20000, 0};
        String[] analyzers = {"english", "standard", "whitespace"};
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--docs" -> docs = Paths.get(args[++i]);
                case "--runs" -> runs = Integer.parseInt(args[++i]);
                case "--sizes" -> sizes = Arrays.stream(args[++i].split(",")).mapToInt(Integer::parseInt).toArray();
                case "--analyzers" -> analyzers = args[++i].split(",");
                default -> throw new IllegalArgumentException("argomento sconosciuto: " + args[i]);
            }
        }
        Files.createDirectories(Paths.get("results"));
        Path tmp = Paths.get("target", "bench-index");
        int all = Indexer.listTxt(docs).size();

        System.out.println("Riscaldamento (run scartato)...");
        Indexer.build(docs, tmp, "english", 0);

        List<String> summary = new ArrayList<>();
        summary.add("analyzer,docs,runs,read_ms,index_ms,commit_ms,total_ms,docs_per_s,index_mb,text_mb");
        try (PrintWriter runsOut = new PrintWriter(Files.newBufferedWriter(Paths.get("results/indexing_runs.csv")))) {
            runsOut.println("analyzer,docs,run,read_ms,index_ms,commit_ms,total_ms,docs_per_s,index_mb");
            for (String a : analyzers) {
                for (int size : sizes) {
                    int n = size <= 0 || size > all ? all : size;
                    double[] read = new double[runs], add = new double[runs], commit = new double[runs],
                            total = new double[runs];
                    Indexer.Stats last = null;
                    for (int r = 0; r < runs; r++) {
                        deleteDir(tmp);
                        Indexer.Stats s = Indexer.build(docs, tmp, a, n);
                        read[r] = s.readNanos() / 1e6;
                        add[r] = s.addNanos() / 1e6;
                        commit[r] = s.commitNanos() / 1e6;
                        total[r] = s.totalNanos() / 1e6;
                        runsOut.printf(Locale.ROOT, "%s,%d,%d,%.1f,%.1f,%.1f,%.1f,%.0f,%.2f%n", a, s.docs(), r + 1,
                                read[r], add[r], commit[r], total[r], s.docsPerSecond(), s.indexBytes() / 1e6);
                        last = s;
                    }
                    double medTotal = median(total);
                    String line = String.format(Locale.ROOT, "%s,%d,%d,%.1f,%.1f,%.1f,%.1f,%.0f,%.2f,%.2f",
                            a, n, runs, median(read), median(add), median(commit), medTotal,
                            n / (medTotal / 1000.0), last.indexBytes() / 1e6, last.bytesRead() / 1e6);
                    summary.add(line);
                    System.out.println(line);
                }
            }
        }
        Files.write(Paths.get("results/indexing_summary.csv"), summary);
        deleteDir(tmp);
        System.out.println("Scritti results/indexing_runs.csv e results/indexing_summary.csv");
    }

    static double median(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        int m = c.length / 2;
        return c.length % 2 == 1 ? c[m] : (c[m - 1] + c[m]) / 2;
    }

    static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
