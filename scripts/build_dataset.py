#!/usr/bin/env python3
"""
Costruzione del dataset per l'Homework 3 (Ingegneria dei Dati).

Scarica dall'API ufficiale di arXiv tutti gli articoli della categoria cs.IR
(Information Retrieval, inclusi i cross-list) e li trasforma in una directory
di file .txt:

    nome del file  = titolo dell'articolo, ripulito per essere un nome di file valido
    contenuto      = abstract dell'articolo

Passi:
  1. download a "fette" temporali (submittedDate), perche' l'API di arXiv
     diventa inaffidabile con offset molto profondi; ogni fetta con piu' di
     MAX_SLICE risultati viene divisa a meta';
  2. salvataggio dei record grezzi in data/raw/arxiv_cs_ir.jsonl.gz; il file e'
     nel repository, quindi le esecuzioni successive ricostruiscono esattamente lo
     stesso dataset senza riscaricare nulla (arXiv aggiunge articoli ogni giorno);
  3. pulizia: deduplicazione per id arXiv e per titolo normalizzato,
     scarto di record senza titolo/abstract, normalizzazione degli spazi;
  4. scrittura dei .txt in data/docs/, di data/docs_map.tsv (file -> id arXiv)
     e di data/dataset_stats.json (statistiche riportate nella relazione).

Usa solo la libreria standard di Python.
Uso:  python3 scripts/build_dataset.py [--out data] [--refresh]
      (--refresh riscarica tutto da arXiv: il dataset ottenuto sara' piu' grande)
"""
import argparse
import gzip
import json
import re
import statistics
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta
from pathlib import Path

API = "https://export.arxiv.org/api/query"
CATEGORY = "cs.IR"
PAGE_SIZE = 1000          # record per richiesta
MAX_SLICE = 8000          # oltre questa soglia una fetta temporale viene divisa
DELAY = 3.1               # secondi tra richieste (policy arXiv: >= 3 s)
NS = {"a": "http://www.w3.org/2005/Atom",
      "os": "http://a9.com/-/spec/opensearch/1.1/",
      "arxiv": "http://arxiv.org/schemas/atom"}
MAX_NAME_LEN = 150        # caratteri massimi del nome file (senza .txt)

_last_call = 0.0


def api_call(query, start, max_results):
    """Esegue una richiesta all'API rispettando la pausa minima tra chiamate."""
    global _last_call
    wait = DELAY - (time.time() - _last_call)
    if wait > 0:
        time.sleep(wait)
    params = urllib.parse.urlencode({
        "search_query": query, "start": start, "max_results": max_results,
        "sortBy": "submittedDate", "sortOrder": "ascending"})
    req = urllib.request.Request(f"{API}?{params}",
                                 headers={"User-Agent": "id-homework3-dataset-builder/1.0"})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                body = r.read()
            _last_call = time.time()
            return ET.fromstring(body)
        except Exception as e:  # rete / XML malformato -> riprova
            _last_call = time.time()
            print(f"    errore ({e}); nuovo tentativo tra {5 * (attempt + 1)} s", file=sys.stderr)
            time.sleep(5 * (attempt + 1))
    raise RuntimeError("API arXiv non raggiungibile")


def parse_entries(root):
    out = []
    for e in root.findall("a:entry", NS):
        aid = e.findtext("a:id", default="", namespaces=NS).strip()
        if "/abs/" not in aid:
            continue
        prim = e.find("arxiv:primary_category", NS)
        out.append({
            "id": aid.split("/abs/")[-1],
            "title": e.findtext("a:title", default="", namespaces=NS),
            "abstract": e.findtext("a:summary", default="", namespaces=NS),
            "published": e.findtext("a:published", default="", namespaces=NS),
            "updated": e.findtext("a:updated", default="", namespaces=NS),
            "primary_category": prim.attrib.get("term") if prim is not None else "",
            "categories": [c.attrib.get("term") for c in e.findall("a:category", NS)],
        })
    return out


def fmt(d):
    return d.strftime("%Y%m%d%H%M")


def slice_query(d0, d1):
    return f"cat:{CATEGORY} AND submittedDate:[{fmt(d0)} TO {fmt(d1)}]"


def total_of(root):
    return int(root.findtext("os:totalResults", default="0", namespaces=NS))


def fetch_slice(d0, d1, sink):
    """Scarica tutti i record sottomessi tra d0 e d1 (estremi inclusi)."""
    q = slice_query(d0, d1)
    root = api_call(q, 0, PAGE_SIZE)
    total = total_of(root)
    if total > MAX_SLICE and (d1 - d0) > timedelta(days=2):
        mid = d0 + (d1 - d0) / 2
        print(f"  {d0:%Y-%m-%d}..{d1:%Y-%m-%d}: {total} record -> divido la fetta")
        fetch_slice(d0, mid, sink)
        fetch_slice(mid + timedelta(minutes=1), d1, sink)
        return
    got = parse_entries(root)
    start = len(got)
    while start < total:
        entries = []
        for attempt in range(4):  # l'API a volte restituisce pagine incomplete
            entries = parse_entries(api_call(q, start, PAGE_SIZE))
            expected = min(PAGE_SIZE, total - start)
            if len(entries) >= expected:
                break
            print(f"    pagina incompleta ({len(entries)}/{expected}), riprovo", file=sys.stderr)
        if not entries:
            print(f"    ATTENZIONE: nessun record da offset {start}, salto il resto della fetta",
                  file=sys.stderr)
            break
        got.extend(entries)
        start += len(entries)
    print(f"  {d0:%Y-%m-%d}..{d1:%Y-%m-%d}: attesi {total}, scaricati {len(got)}")
    sink["expected"] += total
    sink["records"].extend(got)


def download(raw_path):
    sink = {"expected": 0, "records": []}
    now = datetime.utcnow()
    years = [(datetime(1990, 1, 1), datetime(2009, 12, 31, 23, 59))]
    years += [(datetime(y, 1, 1), datetime(y, 12, 31, 23, 59)) for y in range(2010, now.year + 1)]
    for d0, d1 in years:
        fetch_slice(d0, d1, sink)
    raw_path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(raw_path, "wt", encoding="utf-8") as f:
        for r in sink["records"]:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    meta = {"query": f"cat:{CATEGORY}", "downloaded_at": now.isoformat() + "Z",
            "expected_total": sink["expected"], "downloaded": len(sink["records"])}
    (raw_path.parent / "download_meta.json").write_text(json.dumps(meta, indent=2))
    return meta


# ----------------------------------------------------------------- pulizia
def norm_space(s):
    return re.sub(r"\s+", " ", s or "").strip()


GREEK = {"α": "alpha", "β": "beta", "γ": "gamma", "δ": "delta", "ε": "epsilon", "θ": "theta",
         "λ": "lambda", "μ": "mu", "π": "pi", "σ": "sigma", "τ": "tau", "φ": "phi", "ω": "omega"}


def title_to_filename(title):
    """Titolo -> nome file leggibile: ASCII, parole separate da '_', trattini mantenuti."""
    t = title
    for g, rep in GREEK.items():
        t = t.replace(g, f" {rep} ")
    t = unicodedata.normalize("NFKD", t).encode("ascii", "ignore").decode("ascii")
    t = re.sub(r"\\[a-zA-Z]+", " ", t)          # comandi LaTeX (\textit, \mathcal, ...)
    t = re.sub(r"[^A-Za-z0-9\-]+", " ", t)       # tutto il resto diventa separatore
    t = re.sub(r"-{2,}", "-", t)
    words = [w.strip("-") for w in t.split() if w.strip("-")]
    name, length = [], 0
    for w in words:                              # tronca a parola intera
        if length + len(w) + (1 if name else 0) > MAX_NAME_LEN:
            break
        name.append(w)
        length += len(w) + (1 if len(name) > 1 else 0)
    return "_".join(name) or "untitled"


def build(records, out_dir, meta):
    docs_dir = out_dir / "docs"
    docs_dir.mkdir(parents=True, exist_ok=True)
    for old in docs_dir.glob("*.txt"):
        old.unlink()

    stats = {"downloaded_records": len(records), "expected_from_api": meta.get("expected_total")}
    seen_ids, seen_titles, used_names = set(), set(), {}
    dup_id = dup_title = empty = 0
    rows, lengths = [], []
    for r in records:
        base_id = re.sub(r"v\d+$", "", r["id"])
        title, abstract = norm_space(r["title"]), norm_space(r["abstract"])
        if not title or not abstract:
            empty += 1
            continue
        if base_id in seen_ids:
            dup_id += 1
            continue
        tkey = re.sub(r"[^a-z0-9]", "", title.lower())
        if tkey in seen_titles:
            dup_title += 1
            continue
        seen_ids.add(base_id)
        seen_titles.add(tkey)

        stem = title_to_filename(title)
        n = used_names.get(stem.lower(), 0) + 1
        used_names[stem.lower()] = n
        fname = f"{stem}.txt" if n == 1 else f"{stem}_{n}.txt"
        (docs_dir / fname).write_text(abstract + "\n", encoding="utf-8")
        lengths.append(len(abstract.split()))
        rows.append((fname, base_id, r["published"][:4], r["primary_category"],
                     ",".join(r["categories"]), title))

    with (out_dir / "docs_map.tsv").open("w", encoding="utf-8") as f:
        f.write("filename\tarxiv_id\tyear\tprimary_category\tcategories\ttitle\n")
        for row in rows:
            f.write("\t".join(row) + "\n")

    years = {}
    for row in rows:
        years[row[2]] = years.get(row[2], 0) + 1
    stats.update({
        "removed_empty": empty, "removed_duplicate_id": dup_id,
        "removed_duplicate_title": dup_title,
        "filename_collisions_suffixed": sum(v - 1 for v in used_names.values() if v > 1),
        "final_documents": len(rows),
        "primary_cs_ir": sum(1 for r in rows if r[3] == "cs.IR"),
        "abstract_words": {"total": sum(lengths), "mean": round(statistics.mean(lengths), 1),
                           "median": statistics.median(lengths), "min": min(lengths),
                           "max": max(lengths)},
        "docs_dir_bytes": sum(p.stat().st_size for p in docs_dir.glob("*.txt")),
        "documents_per_year": dict(sorted(years.items())),
    })
    (out_dir / "dataset_stats.json").write_text(json.dumps(stats, indent=2))
    return stats


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="data")
    ap.add_argument("--refresh", action="store_true", help="riscarica anche se il raw esiste")
    args = ap.parse_args()
    out = Path(args.out)
    raw = out / "raw" / "arxiv_cs_ir.jsonl.gz"
    if args.refresh or not raw.exists():
        print(f"Download arXiv cat:{CATEGORY} ...")
        meta = download(raw)
    else:
        meta = json.loads((raw.parent / "download_meta.json").read_text())
        print(f"Uso i record gia' scaricati in {raw}")
    with gzip.open(raw, "rt", encoding="utf-8") as f:
        records = [json.loads(l) for l in f]
    stats = build(records, out, meta)
    print(json.dumps(stats, indent=2))


if __name__ == "__main__":
    main()
