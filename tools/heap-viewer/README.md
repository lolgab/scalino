# heap-viewer

Interactive treemap of a scalino program's live heap, sized by **retained
size**. A dominator tree attributes each byte to one owner, so an object that
points at an array is charged that array's bytes without double counting.

## Usage

1. Run your scalino-compiled program with:
   ```
   SCALANATIVE_HEAP_GRAPH_FILE=/tmp/heap.graph ./your-program
   ```
   By default the file is overwritten with the snapshot of the most recent GC
   mark cycle, so its size stays bounded however long the program runs. Set
   `SCALANATIVE_HEAP_GRAPH_APPEND=1` to append every cycle as a time series.
   There is no cost when `SCALANATIVE_HEAP_GRAPH_FILE` is unset.

   The process's PID is always inserted into the filename: `heap.graph`
   becomes `heap.24717.graph`. A single `scalino build`/`run`/`package` spawns
   several Scala Native processes (`scalino-dotc`, `scalino-linkdriver`, ...)
   that would otherwise write to the same path. Use `%p` in the path to choose
   where the PID goes (`/tmp/dumps/%p/heap.graph`); without it the PID goes
   right before the file extension. Each process prints the path it writes to
   on stderr (`[scalino GC] heap graph dump: ...`).
2. Open `index.html` in a browser (no server needed).
3. Load the dump file, pick a cycle, and explore. Click a box to zoom into
   it; the prefix filter highlights matching classes.

   On a toolchain-scale heap (a few million objects/edges) the dominator-tree
   analysis takes several seconds; a progress line shows it is working.

## Format

Produced by `gc/immix/Marker.c` (`GraphDump_*`), one block per mark cycle:

```
--- cycle=N wall_seconds=S ---
O,<addr_hex>,<size_bytes>,<is_array 0|1>,<class_name>
E,<from_addr_hex>,<to_addr_hex>
R,<addr_hex>                      (directly reachable from a root)
--- end cycle=N total_objects=X total_bytes=Y ---
```
