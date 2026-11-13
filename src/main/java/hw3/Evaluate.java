package hw3;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Valutazione del sistema.
 *
 *  pool      : per ogni query di eval/queries.tsv unisce i top-K di piu' configurazioni
 *              (pooling, come nelle campagne TREC) e scrive eval/pool.tsv da giudicare.
 *  metrics   : con i giudizi di eval/qrels.tsv calcola P@5, P@10, Recall@5/@10 (relativa al pool),
 *              AP, RR, nDCG@5/@10 e la frazione di top-5/top-10 giudicata, per ogni configurazione.
 *  knownitem : valutazione automatica "known-item": il titolo di un articolo e' la query sul
 *              campo contenuto, l'unico documento rilevante e' l'articolo stesso.
 *
 * Uso: bin/evaluate.sh pool|metrics|knownitem [opzioni]
 */
public final class Evaluate {

    /** Una configurazione di ricerca: campo + analyzer (e quindi indice). */
    record Run(String name, String field, String variant, boolean pooled) {}

    static final List<Run> RUNS = List.of(
            new Run("contenuto-english", "contenuto", "english", true),
            new Run("contenuto-standard", "contenuto", "standard", true),
            new Run("nome", "nome", "english", true),
            new Run("contenuto-whitespace", "contenuto", "whitespace", false));  // fuori dal pool

    record QuerySpec(String qid, String query, String need) {
        String field() { return query.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT); }
        String terms() { return query.strip().split("\\s+", 2)[1]; }
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("uso: Evaluate pool|metrics|knownitem [opzioni]");
            System.exit(1);
        }
        Map<String, String> opt = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        Files.createDirectories(Paths.get("results"));
        switch (args[0]) {
            case "pool" -> pool(Paths.get(opt.getOrDefault("queries", "eval/queries.tsv")),
                    Integer.parseInt(opt.getOrDefault("depth", "5")),
                    Paths.get(opt.getOrDefault("out", "eval/pool.tsv")));
            case "metrics" -> metrics(Paths.get(opt.getOrDefault("queries", "eval/queries.tsv")),
                    Paths.get(opt.getOrDefault("qrels", "eval/qrels.tsv")));
            case "knownitem" -> knownItem(Paths.get(opt.getOrDefault("map", "data/docs_map.tsv")),
                    Integer.parseInt(opt.getOrDefault("n", "500")),
                    Long.parseLong(opt.getOrDefault("seed", "42")));
            default -> throw new IllegalArgumentException("comando sconosciuto: " + args[0]);
        }
    }

    static List<QuerySpec> readQueries(Path file) throws IOException {
        List<QuerySpec> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("qid\t")) continue;
            String[] f = line.split("\t");
            out.add(new QuerySpec(f[0], f[1], f.length > 2 ? f[2] : ""));
        }
        return out;
    }

    static Map<String, Searcher> openSearchers() throws IOException {
        Map<String, Searcher> s = new HashMap<>();
        for (Run r : RUNS) {
            if (!s.containsKey(r.variant())) {
                s.put(r.variant(), new Searcher(Paths.get("index", r.variant()), r.variant()));
            }
        }
        return s;
    }

    static List<String> ranking(Searcher s, String query, int k) throws IOException {
        List<String> files = new ArrayList<>();
        for (Searcher.Hit h : s.search(query, k).hits()) files.add(h.fileName());
        return files;
    }

    // ------------------------------------------------------------------ pooling
    static void pool(Path queriesFile, int depth, Path out) throws IOException {
        Map<String, Searcher> searchers = openSearchers();
        int total = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("qid\tfilename\truns\tbest_rank");
            for (QuerySpec q : readQueries(queriesFile)) {
                Map<String, Set<String>> runsByDoc = new LinkedHashMap<>();
                Map<String, Integer> bestRank = new HashMap<>();
                for (Run r : RUNS) {
                    if (!r.pooled()) continue;
                    List<String> rk = ranking(searchers.get(r.variant()), r.field() + " " + q.terms(), depth);
                    for (int i = 0; i < rk.size(); i++) {
                        runsByDoc.computeIfAbsent(rk.get(i), x -> new LinkedHashSet<>()).add(r.name());
                        bestRank.merge(rk.get(i), i + 1, Math::min);
                    }
                }
                for (var e : runsByDoc.entrySet()) {
                    w.println(q.qid() + "\t" + e.getKey() + "\t" + String.join(",", e.getValue())
                            + "\t" + bestRank.get(e.getKey()));
                }
                System.out.printf("%s: %d documenti nel pool  (%s)%n", q.qid(), runsByDoc.size(), q.query());
                total += runsByDoc.size();
            }
        }
        for (Searcher s : searchers.values()) s.close();
        System.out.println("Totale documenti da giudicare: " + total + " -> " + out);
    }

    // ------------------------------------------------------------------ metriche
    static Map<String, Map<String, Integer>> readQrels(Path file) throws IOException {
        Map<String, Map<String, Integer>> q = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("qid\t")) continue;
            String[] f = line.split("\t");
            q.computeIfAbsent(f[0], x -> new HashMap<>()).put(f[1], Integer.parseInt(f[2]));
        }
        return q;
    }

    /** Metriche di una ranking rispetto ai giudizi (non giudicato = non rilevante). */
    static double[] scores(List<String> rk, Map<String, Integer> judged) {
        int numRel = (int) judged.values().stream().filter(v -> v > 0).count();
        int relAt5 = 0, relAt10 = 0, judgedAt5 = 0, judgedAt10 = 0, relSoFar = 0;
        double ap = 0, rr = 0, dcg = 0, dcg5 = 0;
        for (int i = 0; i < rk.size(); i++) {
            Integer g = judged.get(rk.get(i));
            int rel = g == null ? 0 : g;
            if (i < 10 && g != null) judgedAt10++;
            if (i < 5 && g != null) judgedAt5++;
            if (rel > 0) {
                relSoFar++;
                ap += (double) relSoFar / (i + 1);
                if (rr == 0) rr = 1.0 / (i + 1);
                if (i < 5) relAt5++;
                if (i < 10) relAt10++;
            }
            if (i < 10) dcg += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
            if (i < 5) dcg5 += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        List<Integer> ideal = new ArrayList<>(judged.values());
        ideal.sort(Collections.reverseOrder());
        double idcg = 0, idcg5 = 0;
        for (int i = 0; i < Math.min(10, ideal.size()); i++) {
            double gain = (Math.pow(2, ideal.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
            idcg += gain;
            if (i < 5) idcg5 += gain;
        }
        int n5 = Math.min(5, rk.size()), n10 = Math.min(10, rk.size());
        return new double[] {
                relAt5 / 5.0, relAt10 / 10.0,
                numRel == 0 ? Double.NaN : (double) relAt5 / numRel,
                numRel == 0 ? Double.NaN : (double) relAt10 / numRel,
                numRel == 0 ? Double.NaN : ap / numRel,
                rr,
                idcg5 == 0 ? Double.NaN : dcg5 / idcg5,
                idcg == 0 ? Double.NaN : dcg / idcg,
                n5 == 0 ? Double.NaN : (double) judgedAt5 / n5,
                n10 == 0 ? Double.NaN : (double) judgedAt10 / n10,
                numRel};
    }

    static final String[] METRICS = {"P@5", "P@10", "R@5", "R@10", "AP", "RR", "nDCG@5", "nDCG@10",
            "judged@5", "judged@10", "num_rel"};

    static void metrics(Path queriesFile, Path qrelsFile) throws IOException {
        Map<String, Map<String, Integer>> qrels = readQrels(qrelsFile);
        Map<String, Searcher> searchers = openSearchers();
        List<QuerySpec> queries = readQueries(queriesFile);
        Map<String, List<double[]>> byRun = new LinkedHashMap<>();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                Paths.get("results/metrics_per_query.csv"), StandardCharsets.UTF_8))) {
            w.println("qid,run,main," + String.join(",", METRICS) + ",total_hits,query");
            for (QuerySpec q : queries) {
                Map<String, Integer> judged = qrels.getOrDefault(q.qid(), Map.of());
                for (Run r : RUNS) {
                    String query = r.field() + " " + q.terms();
                    Searcher.Result res = searchers.get(r.variant()).search(query, 100);
                    List<String> rk = res.hits().stream().map(Searcher.Hit::fileName).toList();
                    double[] s = scores(rk, judged);
                    boolean main = r.variant().equals("english") && r.field().equals(q.field());
                    byRun.computeIfAbsent(r.name(), x -> new ArrayList<>()).add(s);
                    if (main) byRun.computeIfAbsent("query-come-scritta", x -> new ArrayList<>()).add(s);
                    StringBuilder sb = new StringBuilder(q.qid() + "," + r.name() + "," + main);
                    for (double v : s) sb.append(String.format(Locale.ROOT, ",%.4f", v));
                    sb.append(",").append(res.totalHits()).append(",\"").append(query.replace("\"", "\"\"")).append("\"");
                    w.println(sb);
                }
            }
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                Paths.get("results/metrics_summary.csv"), StandardCharsets.UTF_8))) {
            w.println("run,queries," + String.join(",", METRICS));
            System.out.printf("%-22s %4s", "configurazione", "n");
            for (String m : METRICS) System.out.printf(" %9s", m);
            System.out.println();
            for (var e : byRun.entrySet()) {
                double[] mean = new double[METRICS.length];
                int[] cnt = new int[METRICS.length];
                for (double[] s : e.getValue()) {
                    for (int i = 0; i < s.length; i++) {
                        if (!Double.isNaN(s[i])) { mean[i] += s[i]; cnt[i]++; }
                    }
                }
                StringBuilder sb = new StringBuilder(e.getKey() + "," + e.getValue().size());
                System.out.printf("%-22s %4d", e.getKey(), e.getValue().size());
                for (int i = 0; i < mean.length; i++) {
                    double v = cnt[i] == 0 ? Double.NaN : mean[i] / cnt[i];
                    sb.append(String.format(Locale.ROOT, ",%.4f", v));
                    System.out.printf(Locale.ROOT, " %9.3f", v);
                }
                System.out.println();
                w.println(sb);
            }
        }
        for (Searcher s : searchers.values()) s.close();
        System.out.println("Scritti results/metrics_per_query.csv e results/metrics_summary.csv");
    }

    // ------------------------------------------------------------------ known-item
    static void knownItem(Path mapFile, int n, long seed) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(mapFile, StandardCharsets.UTF_8)) {
            if (line.startsWith("filename\t")) continue;
            rows.add(line.split("\t"));
        }
        Collections.shuffle(rows, new Random(seed));
        List<String[]> sample = rows.subList(0, Math.min(n, rows.size()));

        String[] variants = {"english", "standard", "whitespace"};
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                Paths.get("results/knownitem.csv"), StandardCharsets.UTF_8))) {
            w.println("analyzer,queries,MRR@100,S@1,S@10,S@100");
            System.out.printf("%-12s %7s %8s %6s %6s %6s%n", "analyzer", "query", "MRR@100", "S@1", "S@10", "S@100");
            for (String v : variants) {
                double mrr = 0;
                int s1 = 0, s10 = 0, s100 = 0;
                try (Searcher s = new Searcher(Paths.get("index", v), v)) {
                    for (String[] r : sample) {
                        String title = r[5].replace("\"", " ").replaceAll("(^|\\s)[+-]+", " ");
                        List<String> rk = ranking(s, "contenuto " + title, 100);
                        int pos = rk.indexOf(r[0]) + 1;
                        if (pos > 0) {
                            mrr += 1.0 / pos;
                            if (pos == 1) s1++;
                            if (pos <= 10) s10++;
                            s100++;
                        }
                    }
                }
                int m = sample.size();
                w.printf(Locale.ROOT, "%s,%d,%.4f,%.4f,%.4f,%.4f%n", v, m, mrr / m, (double) s1 / m,
                        (double) s10 / m, (double) s100 / m);
                System.out.printf(Locale.ROOT, "%-12s %7d %8.3f %6.3f %6.3f %6.3f%n", v, m, mrr / m,
                        (double) s1 / m, (double) s10 / m, (double) s100 / m);
            }
        }
        System.out.println("Scritto results/knownitem.csv");
    }
}
