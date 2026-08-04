# How much of 1.13 to 26.x is block-state properties

Research of 2026-08-04, four independent routes: a computed diff over the ViaVersion block-state
lists, Chunker source, PaperMC/DataConverter fix classes, and the wiki history. It answers the
question the migration design names as the plan's first job.

**A correction carried in from the session that commissioned this.** Earlier I computed the vanished
block names from the `blocks` list of the ViaVersion mappings and reported **two**, `grass` and
`grass_path`. That is the wrong list: `sign` and `wall_sign` appear in `blockstates` but not in
`blocks`, so the count is **four**, and the two I missed account for 40 of the 42 states lost to a
renamed name. Re-verified after the fact against `m-1.13.json` / `m-26.3.json` — over `blocks` the
difference is `[grass, grass_path]`, over `blockstates` it is
`[grass, grass_path, sign, wall_sign]`.

---

# Blockstate-Properties 1.13 → 26.x: gebündelter Befund

## A) Die Zahl

Ich habe die Registry-Rechnung unabhängig gegen **26.3** nachvollzogen (nicht nur 26.1) und die Streitpunkte am Quelltext geprüft. Skript und Daten: `/tmp/claude-1000/-mnt-projects-oss-onelitefeather-Falco/0e269350-c9d9-4ac0-8181-5b2bd8271309/scratchpad/` (`m-1.13.json` … `m-26.3.json`).

| Fall | Anzahl | betroffene 1.13-Zustände | Beleg |
|---|---|---|---|
| **(a) Property umbenannt** | **0** | 0 | Kein 1.13-Block verliert und gewinnt gleichzeitig einen Schlüssel. Alle 4 Umbenennungen der ganzen Kette betreffen Blöcke, die es 1.13 nicht gab: `jigsaw` facing→orientation (1.16), `creaking_heart` creaking→active→creaking_heart_state (1.21.4/1.21.5), `test_block` test_block_mode→mode (V4305). Drei Wege bestätigen das unabhängig. |
| **(b) Wert weggefallen/geändert** | **2 Blöcke × 4 Properties** | **128** | `cobblestone_wall` / `mossy_cobblestone_wall`: `north/south/east/west` `false,true` → `none,low,tall` (1.16, V2503). `note_block.instrument` wächst nur (10→27 Werte) → **0 betroffene Zustände**. |
| **(c) Property hinzugekommen** | **30 (Block,Key)-Paare über 30 Blöcke** | **258** | `waterlogged` 17×, `powered` 12×, `unstable` 1×. |
| **(c) Property weggefallen** | **1** | **4** | `cauldron` verliert `level` (1.17, V2679). |
| Blockname verschwunden | 4 | 42 | `grass`, `grass_path`, **`sign`**, **`wall_sign`** |
| **registry-sichtbar gesamt** | **37 von 593 Blocknamen** | **432 von 8582 (5,03 %)** | exakt reproduziert, 1.13↔26.1 **und** 1.13↔26.3 identisch |

**Dazu zwei Fälle, die kein Registry-Diff finden kann** — nur DataConverter hat sie:

| Fall | Zustände | Warum unsichtbar |
|---|---|---|
| `stone_slab` → `smooth_stone_slab` (V1802, 1.14) | **6** | Der Name existiert in 1.13 *und* 26.3, bezeichnet aber verschiedene Blöcke. Mengendifferenz zweier Registries ist hier prinzipiell blind. Verifiziert: 1.13 `stone_slab` = 6 Zustände, 26.3 hat `stone_slab` **und** `smooth_stone_slab` mit je 6. |
| `redstone_wire` (V2531, 20w17a) | **144** | Zustandsliste 1.13 und 26.3 **byte-identisch** (1296 = 1296), nur die Bedeutung änderte sich. Ich habe V2531s Logik brute-force nachgerechnet: 9 der 81 Richtungskombinationen ändern sich, × 16 `power` = **144 Zustände**. |

**Endstand: 39 von 593 Blocknamen (6,6 %), 582 von 8582 Zuständen (6,8 %).**

### Welcher Weg trägt — und die Auflösung der Widersprüche

**Keiner allein. Zwei Wege arbeitsteilig:**

- **PaperMC/DataConverter liefert die belastbare Regel*menge***, weil sie per Konstruktion vollständig ist: Mojang *muss* jeden Fix schreiben, sonst laden eigene Welten falsch. Oberhalb `V1_13 = 1519` gibt es abzählbar **6** `BLOCK_STATE.addStructureConverter`-Klassen und **12** Rename-Klassen — per grep abschließend, kein Schätzwert. Nur dieser Weg fand `stone_slab` und `redstone_wire`.
- **Der ViaVersion-Registry-Diff liefert die belastbaren Zustands*zahlen***, weil er alle 8582 Zustände zählt statt Regeln. Er ist aber blind für Semantikänderungen bei gleicher Signatur — nachweislich zweimal.
- **Chunker** ist die unabhängige Bestätigung (deckungsgleich, 22 Versionszweige in einer Datei), aber auf Chunkers Java↔Bedrock-Zwischenmodell zugeschnitten und ohne Vollständigkeitsgarantie — das räumt die Erhebung selbst ein.
- **minecraft.wiki** kann die Zahl nicht liefern (keine versionsübergreifende Blockstate-Seite, drei dokumentierte Lücken/Wartungsbanner), taugt nur zur snapshot-genauen Datierung.

**Widerspruch 1 — `chain` → `iron_chain`: aufgelöst, irrelevant.** Chunker und DataConverter warnen, die Registry-Diffs fanden es nicht. Grund: `minecraft:chain` **existiert in 1.13 nicht** — geprüft, erstmals in `mapping-1.16.json`. Für eine 1.13-Quelle kein Fall. (Chunkers eigener Befund „CHAIN +axis ab 1.16.2" bestätigt es.)

**Widerspruch 2 — verschwundene Blocknamen: die Prämisse ist falsch.** Es sind **vier**, nicht zwei: `sign`→`oak_sign` und `wall_sign`→`oak_wall_sign` (V1802, 18w43a) fehlen in eurer Liste und machen 40 der 42 namensbedingt verlorenen Zustände aus. Drei der vier Wege sagen das übereinstimmend. Plus `stone_slab` als fünften, stillen Fall.

**Widerspruch 3 — brauchen Wände Nachbarschaftskontext? Nein, widerlegt.** Der Wiki-Weg behauptet es; DataConverter V2503 und Chunkers `BOOL_TO_WALL_HEIGHT` machen beide reines Lookup `true→low`, `false→none`. Ich habe geprüft: `up` existiert in 1.13 **und** 26.3 unverändert und wird 1:1 übernommen — die 20w06a-Renderänderung betrifft `up`, nicht die vier Richtungen. `tall` ist nicht rekonstruierbar und Mojang versucht es nicht. Verlustbehaftet, aber Lookup.

**Kleinkorrektur:** Der Wiki-Weg nennt 254 Auffüllungs-Zustände, korrekt sind **258** (1 barrier + 1 conduit + 5 Korallen + 84 Blätter + 46 Schienen + 120 Köpfe + 1 tnt). 258 + 4 (cauldron) = 262, was der Rechenweg-Erhebung entspricht.

---

## Der Befund, den keine der vier Erhebungen hatte: Minestom ist nicht Vanilla

Die DataConverter-Erhebung markierte selbst als ungeprüft, ob Vanillas Toleranz für Minestom gilt. Ich habe es nachgesehen (`net.minestom:minestom:2026.06.05-26.1.2` Sources, `net.minestom:data:26.2-rv3`):

- `AnvilLoader.loadBlockPalette` (Z. 254–286) macht `Block.fromKey(name)` → `withProperties(nbtProperties)`. **Kein try/catch.**
- `BlockImpl.withProperties` ruft `findKeyIndexThrow` / `findValueIndexThrow` — beide werfen `IllegalArgumentException` bei unbekanntem Schlüssel **oder** unbekanntem Wert (Z. 292–306).
- Unbekannter Blockname → `Objects.requireNonNull(..., "Unknown block " + blockName)` → NPE.

Damit ist die Lage für Falco **umgekehrt zu Vanilla**:

| Fall | Vanilla / DFU | Minestom-Loader |
|---|---|---|
| Property **fehlt** (30 Blöcke, 258 Zustände) | Default | **Default — identisch, gratis** |
| Property **überzählig** (`cauldron[level]`, 4 Zustände) | still ignoriert | **Exception, Chunk lädt nicht** |
| Wert unbekannt (Wände `north=true`, 128 Zustände) | — (DFU fixt vorher) | **Exception, Chunk lädt nicht** |
| Name unbekannt (4 Namen, 42 Zustände) | — | **NPE** |

**Die gute Hälfte davon ist geschenkt:** Ich habe die 26.2-Defaults aller 30 Auffüllungs-Blöcke gegen Minestoms `block.json` geprüft. Minestom setzt sie automatisch, und in allen 30 Fällen ist der Ziel-Default auch semantisch richtig. **Aber die verkürzte Regel „Default ist immer `false`" aus zwei Erhebungen stimmt nicht**: Korallen und `conduit` haben `waterlogged=**true**` als Default — was für 1.13.0-Daten genau richtig ist (eine trockene lebende Koralle starb ab). Wer `false` hart einträgt, trocknet jedes Riff aus. Die richtige Regel heißt **„Default der Zielversion", nicht „false"**.

---

## B) Tabelle oder Code

**Kein einziger Fall braucht Nachbarschafts- oder Chunk-Kontext.** Der einzige Fix der ganzen DataConverter-Historie, den eine Tabelle prinzipiell nicht ausdrücken kann, ist V1496 (LeavesFix, Flood-Fill über den Chunk) — DataVersion 1496 < 1519, **außerhalb eurer Spanne**, eine 1.13-Release-Welt hat ihn hinter sich.

Alle 8 Regeln sind als **Zustand→Zustand**-Abbildung darstellbar. Drei davon sind **nicht** als Property→Property-Tabelle darstellbar — das ist die architekturrelevante Unterscheidung:

1. **`cauldron` (4 Zustände)** — ein Property-Wert entscheidet den *Blocknamen*: `level=0` → `cauldron`, `level=1..3` → `water_cauldron[level=n]`. Namens- und Property-Ebene gekoppelt. Genau deshalb war der Fall im Namensdiff unsichtbar.
2. **`redstone_wire` (144 Zustände)** — der neue Wert einer Richtung hängt von den *anderen drei Richtungen desselben Zustands* ab (`connectedX`/`connectedZ` in V2531). Pro Property implementiert wird es garantiert falsch; als Voll-State-Tabelle über 1296 Einträge korrekt.
3. **`stone_slab` (6 Zustände)** — reine Namensregel, aber **nur an der Schwelle 1.13→1.14 gültig**. Eine unversionierte Regel würde `stone_slab` aus einer 1.16-Welt fälschlich umbenennen. Der Beweis, dass Regeln versioniert aufgelöst werden müssen.

Die restlichen 5 (4 Namensregeln, 2 Wandtabellen als eine geteilte Tabelle, 30 Auffüllungen) sind reine Daten — und die 30 Auffüllungen sind bei Minestom sogar **null Zeilen**, weil `withProperties` vom Default-State ausgeht.

---

## C) Folge für den Plan

**Randthema in der Menge, aber kein Fall für eine formlose Handtabelle.** Empfehlung, dreiteilig:

1. **Nicht die volle Chunker-Strategie portieren.** Chunkers Wert (~2.000 Zeilen inkl. `VanillaBlockStates`-Vokabular, MIT) liegt in Java↔Bedrock-Übersetzung. Falco macht Java→Java und bräuchte `group(1.13)` invertiert und mit `group(26.x)` komponiert — Aufwand ohne Gegenwert. Die 22 Fakten selbst sind unter 100 Zeilen Nutzinformation und abschreibbar.

2. **Aber die *Mechanik* sofort bauen, nicht die Tabelle allein.** Drei Eigenschaften sind von Anfang an nötig, jede durch je einen konkreten Fall erzwungen — nachrüsten heißt umbauen:
   - **Regeln sind versioniert**, aufgelöst per „größte Regelversion ≤ Quellversion" (Chunkers `VersionedStateMappingGroup`, ~94 Zeilen). Erzwungen von `stone_slab`.
   - **Der Schlüssel ist der ganze Zustand, nicht eine Property.** Erzwungen von `cauldron` und `redstone_wire`. Das ist DataConverters Modell (`BLOCK_STATE.addStructureConverter`) und kostet nichts extra.
   - **Eine Regel darf den Blocknamen ändern.** Erzwungen von `cauldron`.
   
   Das sind grob 200–300 Zeilen Engine plus eine Regeldatei. **(Zeilenzahl: Schätzung.)**

3. **Auffüllung fehlender Properties gar nicht implementieren.** Minestoms Loader erledigt sie über `defaultState`, korrekt in allen 30 Fällen (verifiziert gegen `data-26.2-rv3`). Das streicht 258 der 582 Zustände ersatzlos aus dem Plan. **Aber: einen Test schreiben, der genau das festnagelt** — insbesondere `waterlogged=true` bei Korallen/conduit, das der intuitiven Annahme widerspricht.

**Priorität nach Schadensbild, nicht nach Zustandszahl.** Bei Minestom sind 178 Zustände (4 Namen + 128 Wände + 4 cauldron + 6 stone_slab, letztere semantisch) **ladeblockierend** — eine 1.13-Welt mit einer Kopfsteinmauer bricht den Chunk ab, nicht nur den Block. `redstone_wire` (144) lädt sauber und sieht falsch aus. Die Reihenfolge ist damit: Namen → Wände → cauldron → stone_slab → redstone_wire.

**Der Hauptteil der Arbeit liegt woanders**, und darin sind alle vier Wege einig: BlockEntities (NBT-Struktur, nicht registry-diffbar), Biome, und vor allem das **Chunk-Containerformat** — DataConverters V2832 (1.18-Höhenerweiterung, Paletten, Bit-Storage, Heightmaps) ist mit 917 Zeilen allein größer als die gesamte Blockstate-Arbeit; 186 der 204 relevanten Fix-Klassen fassen Blockinhalte überhaupt nicht an. Blockstates sind **etwa 3 % der Konverterarbeit** — die Prozentzahl ist eine Schätzung, das Verhältnis 6/204 Fix-Klassen ist belegt.

---

## D) Was unbelegt blieb

| Aussage | Status |
|---|---|
| „~200–300 Zeilen Engine", „~2.000 Zeilen für die Chunker-Portierung", „Blockstates ≈ 3 % der Konverterarbeit", „in einem Tag erledigt" | **Schätzungen.** Kein Weg belegt Aufwand, nur Umfang. |
| Vollständigkeit **nach** DataVersion 4661 | **Offen.** Der DataConverter-Klon (Commit `dcde1f1f`, 2026-03-16) deckt bis V4661; die Registry-Diffs bis 26.3. Ob 26.4+ weitere Blockstate-Fixes bringt: ungeprüft. |
| Genauer Quellstand „1.13" | **Unbestimmt.** `waterlogged` an Korallen/conduit und `unstable` an tnt kamen in **1.13.1** (18w30a, per Wiki datiert). Ist die Quelle 1.13.2, entfallen 7 der 30 Auffüllungen. Folgenlos, weil Auffüllung ohnehin gratis ist. |
| Falcos genaue Zielversion (26.1 / 26.2 / 26.3) | **Nicht spezifiziert.** Ich habe 1.13↔26.1 und 1.13↔26.3 gerechnet: für 1.13-Blöcke **identisches Ergebnis** (432/37), die Wahl ist für diese Frage folgenlos. |
| Optische Auswirkung von `low` statt `tall` bei gestapelten Wänden | **Nicht gemessen.** Mojang und Chunker akzeptieren `low`; ob das in gebauten Welten stört, sagt keine Quelle. Ein nachgelagerter Nachbarschaftspass wäre optional möglich. |
| Ob Minestoms `defaultStateId`-Verhalten sich zwischen 26.1.2 und 26.2/26.3 ändert | **Ungeprüft.** Gelesen wurde `minestom 2026.06.05-26.1.2` + `data 26.2-rv3` aus dem lokalen Gradle-Cache. |
| Ob es außerhalb von Blöcken (Items in Truhen, BlockEntities) weitere stille Umdeutungen wie `stone_slab` gibt | **Außerhalb des Auftrags, ungeprüft.** V1802 fasst auch Items an. |