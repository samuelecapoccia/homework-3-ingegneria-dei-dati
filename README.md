# Homework 3 – Ingegneria dei Dati 2026/2027

Motore di ricerca su file `.txt` realizzato con **Apache Lucene 10.6.0** (Java 21+).

Il dataset è costituito da **28.162 articoli arXiv della categoria cs.IR** (Information Retrieval).
Ogni articolo è un file `.txt`: il **nome del file** è il titolo dell'articolo, il **contenuto** è l'abstract.

## Struttura

| Percorso | Contenuto |
|---|---|
| `scripts/build_dataset.py` | Crea `data/docs/*.txt`, `data/docs_map.tsv` e `data/dataset_stats.json` dai record arXiv salvati in `data/raw/arxiv_cs_ir.jsonl.gz` |
| `data/raw/arxiv_cs_ir.jsonl.gz` | I 28.167 record della categoria cs.IR scaricati da arXiv il 13/11/2026 (compressi) |
| `src/main/java/hw3/Analyzers.java` | Gli analyzer dei due campi (`nome`, `contenuto`) |
| `src/main/java/hw3/Indexer.java` | Indicizza una directory di file `.txt` |
| `src/main/java/hw3/HwQueryParser.java` | Il parser della sintassi di query |
| `src/main/java/hw3/Searcher.java` | Legge le query da console e stampa i risultati |
| `src/main/java/hw3/IndexingBenchmark.java` | Misura i tempi di indicizzazione |
| `src/main/java/hw3/Evaluate.java` | Pooling, metriche (P@k, Recall, MAP, MRR, nDCG) e valutazione known-item |
| `scripts/judge.py` | Strumento da terminale per i giudizi di rilevanza |
| `scripts/plot_indexing.py` | Grafico dei tempi di indicizzazione (`results/indexing_times.png`) |
| `eval/` | Le query di valutazione, il pool e i giudizi (`qrels.tsv`) |
| `results/` | I risultati degli esperimenti in formato CSV |

## Come riprodurre

Prerequisiti: Java 21 o superiore, Maven, Python 3. `build_dataset.py` e `judge.py` usano solo la libreria standard; `plot_indexing.py` richiede matplotlib.

```bash
python3 scripts/build_dataset.py          # pochi secondi: crea data/docs/ dai record salvati
mvn -q package                            # compila
bin/index.sh --analyzer english           # crea index/english (indice principale)
bin/search.sh                             # ricerca interattiva
```

Esperimenti:

```bash
bin/index.sh --analyzer standard && bin/index.sh --analyzer whitespace   # indici di confronto
bin/benchmark.sh                          # tempi di indicizzazione  -> results/indexing_*.csv
bin/evaluate.sh pool                      # pool da giudicare         -> eval/pool.tsv
python3 scripts/judge.py                  # giudizi di rilevanza      -> eval/qrels.tsv
bin/evaluate.sh metrics                   # metriche                  -> results/metrics_*.csv
bin/evaluate.sh knownitem                 # known-item search         -> results/knownitem.csv
bin/analyze.sh "Open-Domain_Question_Answering"   # mostra i token prodotti dagli analyzer
python3 scripts/plot_indexing.py          # grafico dei tempi         -> results/indexing_times.png
```

`python3 scripts/build_dataset.py --refresh` riscarica tutto da arXiv (qualche minuto). Il dataset ottenuto sarà più grande di quello usato nella relazione, perché la categoria cresce ogni giorno.

La relazione è in [`relazione/Relazione_Homework3.pdf`](relazione/Relazione_Homework3.pdf).

## Sintassi delle query

```
nome|contenuto  termine  "frase esatta"  +obbligatorio  -escluso
```

- `nome` cerca nel nome del file, `contenuto` cerca nel testo.
- Le parole tra virgolette formano una **phrase query**.
- Gli elementi senza prefisso sono opzionali: più ne trova un documento, più alto è il suo punteggio (BM25).
- `+` rende un elemento obbligatorio, `-` lo esclude.

Esempi:

```
contenuto "query expansion" pseudo relevance feedback
nome +recommender survey -music
```

Comandi della console: `:help`, `:k N` (numero di risultati), `:explain R` (spiega il punteggio del risultato R), `:quit`.
