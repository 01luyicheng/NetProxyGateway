## 2024-05-18 - Optimizing Database Updates in Go Server
**Learning:** Precompiling SQL prepared statements (`db.Prepare`) for database operations in frequently executed code paths and storing them (e.g. in a server struct) drastically reduces repetitive query parsing overhead.
**Action:** Identify database calls, particularly inside tight loops or high-throughput sections (like session comparisons). Optimize them by saving and reusing prepared statements in struct variables during server initialization. Ensure properly closing prepared statements using `.Close()` during shutdown.
