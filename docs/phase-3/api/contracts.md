# Immutable API snapshots

The committed, credential-free full exports are [OpenAPI YAML](openapi-7ca6e294.yaml) and [Postman JSON](postman-7ca6e294.json). They are byte-for-byte snapshots of `docs/openapi.yaml` and `postman/Oficina-Mecanica.postman_collection.json` at APP revision `7ca6e2948e423ea171c252eddeaca266179bd153`. The sources contain only contract examples/placeholders, never usable user data, tokens, API keys, or passwords.

The source receipts remain available as [OpenAPI hash](openapi-7ca6e294.sha256) and [Postman source hashes](postman-7ca6e294.sha256). Run `python scripts/verify-api-snapshots.py` from the APP root to compare both exports against the immutable Git revision. These are source artifacts, not claims of a deployed endpoint.
