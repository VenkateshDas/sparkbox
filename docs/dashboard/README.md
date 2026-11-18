# Sparkbox dashboard preview

Generated vault snapshots are private and excluded from Git. On a fresh checkout, generate your own before opening the preview:

```sh
python3 docs/dashboard/build_snapshot.py '/path/to/vault'
```

Open `index.html` directly, or serve this folder:

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory docs/dashboard
```

Then open <http://127.0.0.1:8765>.

The default view uses an actual Mac vault snapshot. Scout costs and private Android usage are unavailable. **Explore demo usage** enables clearly labelled synthetic requests and activity. Demo prices are invented, not model price recommendations. This preview has no backend, provider calls, automatic repairs or writes to the vault.

Try section navigation, period selection, Vault search/type/quality/sort filters, note details, chart details, Scout model/purpose filters, request/session details, feature cost details, the demo allowance control, theme switching and filtered JSON export. Some cards explain planned native features rather than simulate data that was not collected. Exported local note metadata should be reviewed before sharing.

Expanded cost tracking: open **Costs**, enable **Explore demo usage**, then filter **Capture processing**, **Card creation**, **Scout interaction** or another workflow. Inspect stage charges and input/output tokens, complete-operation averages, card-stage cost/tokens per saved card, retry/failure spend, operation cost percentiles and operation timelines. Request rows also link to their parent operation. Unknown audio tokens are preserved as unavailable. Whole-operation averages exclude partial model-filtered operations and incomplete reporting; sample counts are shown. Workflow and stage views reconcile to the same requests and must not be added together. The expanded production data contract is in `PLAN.md`.

`PLAN.md` contains research, metric definitions, evidence, scope, phased implementation, accounting rules, edge states and acceptance criteria. `snapshot.json` holds reproducible counts and known limitations; `snapshot.js` makes the preview work without fetch or a server.

Refresh the read-only snapshot:

```sh
python3 docs/dashboard/build_snapshot.py '/path/to/vault'
node docs/dashboard/check.cjs
```

PyYAML was already available on the research machine. Production Android should reuse its Java parsers, not port this research script. Snapshot scope is Knowledge/ and legacy Sources/. Research revision is c844471. Phone sync is not verified.

Validation: JavaScript syntax and the dependency-free reducer/interaction checks pass. Automated browser and screenshot APIs were unavailable in this session, so desktop/mobile visual rendering is **not verified**. The CSS includes phone layout, overflow containers, keyboard focus, light/dark palettes and reduced-motion handling. This is a reviewable mockup, not a qualified Android release.
