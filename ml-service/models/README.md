# ML Runtime Artifacts

This directory is the default local development output path for generated ML
feature snapshots and model bundles. Generated files here are runtime artifacts,
not source fixtures, and are ignored by Git.

Use temporary directories in tests. Production-like Docker and Kubernetes
profiles must use `ML_ARTIFACT_STORE=s3`.
