#!/usr/bin/env python3
"""Grafico dei tempi di indicizzazione (results/indexing_summary.csv -> results/indexing_times.png).
Richiede matplotlib."""
import csv
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
rows = list(csv.DictReader((ROOT / "results/indexing_summary.csv").open()))
colors = {"english": "#2a6fdb", "standard": "#e07b39", "whitespace": "#7a7a7a"}

fig, ax = plt.subplots(figsize=(7.2, 3.6), dpi=200)
for a in ["english", "standard", "whitespace"]:
    pts = [(int(r["docs"]), float(r["total_ms"]) / 1000) for r in rows if r["analyzer"] == a]
    xs, ys = zip(*pts)
    ax.plot(xs, ys, marker="o", lw=2, ms=4, color=colors[a], label=a)
    dy = {"english": -3, "standard": 3, "whitespace": -10}[a]
    ax.annotate(f"{ys[-1]:.2f} s", (xs[-1], ys[-1]), textcoords="offset points", xytext=(6, dy),
                fontsize=8, color=colors[a])
ax.set_xlabel("Documenti indicizzati")
ax.set_ylabel("Tempo totale (s)")
ax.set_xlim(0, 31500)
ax.set_ylim(0, None)
ax.grid(axis="y", color="#dddddd", lw=0.8)
ax.spines[["top", "right"]].set_visible(False)
ax.legend(title="analyzer contenuto", frameon=False, fontsize=8, title_fontsize=8)
ax.set_title("Tempo di indicizzazione (mediana di 5 run)", fontsize=10, loc="left")
fig.tight_layout()
fig.savefig(ROOT / "results/indexing_times.png")
print("Scritto results/indexing_times.png")
