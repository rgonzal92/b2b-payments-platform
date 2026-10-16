# Build only the Issuer's side; simulate the Network, Acquirers and ERPs

This platform plays one Issuer serving many Buyers. The Network, the Acquirers and the Buyers' ERPs are simulators we write. The project's goals (real-time Authorization, money correctness, Reconciliation) all sit on the Issuer's side, and building a real Network or a platform that serves many Issuers would double the scope without adding to them. Serving more than one Issuer is out of scope.
